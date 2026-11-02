package architecture

import upickle.default.ReadWriter

enum BlastLevel derives ReadWriter {
  case Low, Medium, High
}

enum Irreversible derives ReadWriter {
  case Migrated(
    migration: String,
    verb: String,
    target: String)
  case EditedMigration(migration: String)
  case RemovedMigration(migration: String)
  case Payload(event: String)
}

final case class BlastRadius(
  level: BlastLevel,
  changed: List[String],
  added: List[String],
  reached: List[String],
  modules: Int,
  irreversible: List[Irreversible],
  untraced: List[String])
    derives ReadWriter

object BlastRadius {
  private val Listed = 10

  def of(
    diff: DiffModel,
    base: ArchModel,
    head: ArchModel
  ): BlastRadius = {
    val existing         = base.modules.map(_.id).toSet
    val (changed, added) = diff.modules.filter(m => m.status != Status.Unchanged || m.changedFiles.nonEmpty).map(_.id).partition(existing)
    val migrated         = ArchDiff.migratedTables(diff.migrations)
    val touched          =
      diff.tables.filter(t => differs(t)(_.owner) || ArchDiff.columnChanges(t).nonEmpty || migrated(t.key)).flatMap(modulesOn(_)(_.users)) ++
        diff.topics
          .filter(differs(_)(topic => (Trees.unqualified(topic.eventType), topic.owner, topic.payloadDigest)))
          .flatMap(modulesOn(_)(topic => topic.publishers ++ topic.subscribers)) ++
        diff.events
          .filter(differs(_)(event => (Trees.unqualified(event.typeName), event.owner, event.payloadDigest)))
          .flatMap(modulesOn(_)(event => event.publishers ++ event.subscribers.map(_.site)))
    val (was, is)    = (definitions(base), definitions(head))
    val redefined    = changes(byName(was), byName(is))
    val imports      = (base.modules ++ head.modules).flatMap(module => module.outsideImports.map(module.id -> _))
    val importers    = imports.collect { case (id, imported) if redefined(imported) => id }
    val dependents   = (ModuleGraph.of(base).pairs ++ ModuleGraph.of(head).pairs).collect { case (from, to) if changed.contains(to) => from }
    val reached      = ((touched ++ importers ++ dependents).toSet -- changed -- added).toList.sorted
    val modules      = existing.size
    val affected     = changed.size + reached.size
    val irreversible = migrationChanges(diff.migrations) ++ payloadChanges(diff.events)
    val level        =
      if (irreversible.nonEmpty || (affected > 0 && affected * 2 >= modules)) BlastLevel.High
      else if (reached.nonEmpty || changed.size >= 2) BlastLevel.Medium
      else BlastLevel.Low
    val imported = imports.map(_._2).toSet
    val untraced = diff.changedOutside.filterNot(path => changes(was.getOrElse(path, Map.empty), is.getOrElse(path, Map.empty)).exists(imported))
    BlastRadius(level, changed.sorted, added.sorted, reached, modules, irreversible, untraced)
  }

  def summary(radius: BlastRadius, code: String => String): String = {
    val existing = radius.modules
    val others   = existing - radius.changed.size
    val changes  = List(
      Option.when(radius.changed.nonEmpty)(radius.changed match {
        case List(id)                    => s"changes ${code(id)}"
        case ids if ids.size == existing => "changes every module"
        case ids                         => s"changes ${ids.size} of the $existing modules (${listed(ids, code)})"
      }),
      Option.when(radius.added.nonEmpty)(radius.added match {
        case List(id) => s"adds ${code(id)}"
        case ids      => s"adds ${ids.size} modules (${listed(ids, code)})"
      })
    ).flatten
    val what  = if (changes.isEmpty) "No module changed." else s"It ${changes.mkString(" and ")}."
    val scope = if (changes.isEmpty) "modules" else "other modules"
    val reach =
      if (radius.reached.nonEmpty)
        Some(
          s"${radius.reached.size} of the $others $scope ${if (radius.reached.size == 1) "depends" else "depend"} on what changed: ${listed(radius.reached, code)}."
        )
      else Option.when(changes.nonEmpty && others > 0)("No other module depends on what changed.")
    val undo     = Option.when(radius.irreversible.nonEmpty)(s"Hard to undo: ${radius.irreversible.map(describe(_, code)).mkString("; ")}.")
    val untraced = Option.when(radius.untraced.nonEmpty)("Code that no module imports changed too, which this doesn't trace.")
    (what :: reach.toList ++ undo.toList ++ untraced.toList).mkString(" ")
  }

  private def listed(ids: List[String], code: String => String): String = {
    val (shown, rest) = ids.splitAt(Listed)
    (shown.map(code) ++ Option.when(rest.nonEmpty)(s"${rest.size} more")).mkString(", ")
  }

  private def describe(item: Irreversible, code: String => String): String = item match {
    case Irreversible.Migrated(migration, verb, target) => s"${code(migration)} $verb ${code(target)}"
    case Irreversible.EditedMigration(migration)        => s"${code(migration)} was edited, though it may already have run"
    case Irreversible.RemovedMigration(migration)       => s"${code(migration)} was removed, though it may already have run"
    case Irreversible.Payload(event)                    => s"the payload of outbox event ${code(event)} changed, and stored rows keep the old shape"
  }

  private def definitions(model: ArchModel): Map[String, Map[OutsideImport, String]] =
    model.outsideModules.map(file => file.path -> file.definitions.map((name, digest) => OutsideImport(file.pkg, name) -> digest)).toMap

  private def byName(definitions: Map[String, Map[OutsideImport, String]]): Map[OutsideImport, List[String]] =
    definitions.values.flatten.toList.groupMap(_._1)(_._2).view.mapValues(_.sorted).toMap

  private def changes[K, V](was: Map[K, V], is: Map[K, V]): Set[K] = (was.keySet ++ is.keySet).filter(key => was.get(key) != is.get(key))

  private def differs[A, D](node: NodeDiff[A])(definition: A => D): Boolean = node.base.map(definition) != node.head.map(definition)

  private def modulesOn[A](node: NodeDiff[A])(sites: A => List[Site]): List[String] =
    (node.base.toList ++ node.head.toList).flatMap(sites).collect { case Site(Owner.Module(id), _, _) => id }.distinct

  private def migrationChanges(migrations: List[NodeDiff[Migration]]): List[Irreversible] =
    migrations.flatMap { migration =>
      migration.status match {
        case Status.Changed if MigrationExtractor.versionOf(migration.key).isDefined => List(Irreversible.EditedMigration(migration.key))
        case Status.Added | Status.Changed                                           =>
          val before = migration.base.toList.flatMap(_.destructive)
          migration.head.toList.flatMap(_.destructive).filterNot(before.contains).map(d => Irreversible.Migrated(migration.key, d.verb, d.target))
        case Status.Removed   => List(Irreversible.RemovedMigration(migration.key))
        case Status.Unchanged => Nil
      }
    }

  private def payloadChanges(events: List[NodeDiff[OutboxEvent]]): List[Irreversible] =
    events.collect {
      case NodeDiff(key, _, Some(before), Some(after), _)
          if before.payloadDigest.zip(after.payloadDigest).exists(_ != _) || Trees
            .unqualified(before.typeName) != Trees.unqualified(after.typeName) =>
        Irreversible.Payload(key)
    }
}
