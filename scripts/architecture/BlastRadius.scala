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
    val touched          = diff.tables.filter(moved).flatMap(modulesOn(_)(_.users)) ++
      diff.topics.filter(moved).flatMap(modulesOn(_)(topic => topic.publishers ++ topic.subscribers)) ++
      diff.events.filter(moved).flatMap(modulesOn(_)(event => event.publishers ++ event.subscribers.map(_.site)))
    val (was, is)    = (definitions(base), definitions(head))
    val redefined    = (was.keySet ++ is.keySet).filter(key => was.get(key) != is.get(key))
    val importers    = (base.modules ++ head.modules).filter(_.outsideImports.exists(redefined)).map(_.id)
    val dependents   = (ModuleGraph.of(base).pairs ++ ModuleGraph.of(head).pairs).collect { case (from, to) if changed.contains(to) => from }
    val reached      = ((touched ++ importers ++ dependents).toSet -- changed -- added).toList.sorted
    val modules      = (existing ++ head.modules.map(_.id)).size
    val affected     = changed.size + reached.size
    val irreversible = migrationChanges(diff.migrations) ++ payloadChanges(diff.events)
    val level        =
      if (irreversible.nonEmpty || (affected > 0 && affected * 2 >= modules)) BlastLevel.High
      else if (reached.nonEmpty || changed.size >= 2) BlastLevel.Medium
      else BlastLevel.Low
    val imported = (base.modules ++ head.modules).flatMap(_.outsideImports).filter(redefined).map(_.path).toSet
    BlastRadius(level, changed.sorted, added.sorted, reached, modules, irreversible, diff.changedOutside.filterNot(imported))
  }

  def summary(radius: BlastRadius, code: String => String): String = {
    val existing = radius.modules - radius.added.size
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

  private def definitions(model: ArchModel): Map[OutsideImport, String] =
    model.outsideModules.flatMap(file => file.definitions.map((name, digest) => OutsideImport(file.path, name) -> digest)).toMap

  private def moved[A](node: NodeDiff[A]): Boolean = node.status != Status.Unchanged || node.codeChanged

  private def modulesOn[A](node: NodeDiff[A])(sites: A => List[Site]): List[String] =
    (node.base.toList ++ node.head.toList).flatMap(sites).collect { case Site(Owner.Module(id), _, _) => id }.distinct

  private def migrationChanges(migrations: List[NodeDiff[Migration]]): List[Irreversible] =
    migrations.flatMap { migration =>
      migration.status match {
        case Status.Added     => migration.head.toList.flatMap(_.destructive).map(d => Irreversible.Migrated(migration.key, d.verb, d.target))
        case Status.Changed   => List(Irreversible.EditedMigration(migration.key))
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
