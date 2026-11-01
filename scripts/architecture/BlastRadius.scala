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
  reached: List[String],
  others: Int,
  irreversible: List[Irreversible],
  outsideChanged: Boolean)
    derives ReadWriter

object BlastRadius {
  def of(
    diff: DiffModel,
    base: ArchModel,
    head: ArchModel
  ): BlastRadius = {
    val changed = diff.modules.filter(m => m.status != Status.Unchanged || m.changedFiles.nonEmpty).map(_.id)
    val touched = diff.tables.filter(moved).flatMap(modulesOn(_)(_.users)) ++
      diff.topics.filter(moved).flatMap(modulesOn(_)(topic => topic.publishers ++ topic.subscribers)) ++
      diff.events.filter(moved).flatMap(modulesOn(_)(event => event.publishers ++ event.subscribers.map(_.site)))
    val dependents   = (ModuleGraph.of(base).pairs ++ ModuleGraph.of(head).pairs).groupMap(_._2)(_._1).view.mapValues(_.toSet).toMap
    val reached      = ((touched.toSet ++ ModuleGraph.reach(changed.toSet ++ touched, dependents, Set.empty)) -- changed).toList.sorted
    val others       = (base.modules ++ head.modules).map(_.id).distinct.size - changed.size
    val irreversible = migrationChanges(diff.migrations) ++ payloadChanges(diff.events)
    val level        =
      if (irreversible.nonEmpty || (reached.nonEmpty && reached.size * 2 >= others)) BlastLevel.High
      else if (reached.nonEmpty) BlastLevel.Medium
      else BlastLevel.Low
    BlastRadius(level, changed.sorted, reached, others, irreversible, diff.changedOutside.nonEmpty)
  }

  def summary(radius: BlastRadius, code: String => String): String = {
    val scope = if (radius.changed.isEmpty) "modules" else "other modules"
    val reach =
      if (radius.reached.nonEmpty)
        s"${radius.reached.size} of the ${radius.others} $scope ${if (radius.reached.size == 1) "depends" else "depend"} on what changed: ${radius.reached.map(code).mkString(", ")}."
      else if (radius.changed.nonEmpty) "No other module depends on what changed."
      else "No module changed."
    val undo    = Option.when(radius.irreversible.nonEmpty)(s"Hard to undo: ${radius.irreversible.map(describe(_, code)).mkString("; ")}.")
    val outside = Option.when(radius.outsideChanged)("Code outside the modules changed too, which this doesn't trace.")
    (reach :: undo.toList ++ outside.toList).mkString(" ")
  }

  private def describe(item: Irreversible, code: String => String): String = item match {
    case Irreversible.Migrated(migration, verb, target) => s"${code(migration)} $verb ${code(target)}"
    case Irreversible.EditedMigration(migration)        => s"${code(migration)} was edited, though it may already have run"
    case Irreversible.RemovedMigration(migration)       => s"${code(migration)} was removed, though it may already have run"
    case Irreversible.Payload(event)                    => s"the payload of outbox event ${code(event)} changed, and stored rows keep the old shape"
  }

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
