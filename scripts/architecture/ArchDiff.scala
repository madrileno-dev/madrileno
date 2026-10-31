package architecture

import upickle.implicits.serializeDefaults

import upickle.default.ReadWriter

enum Status derives ReadWriter {
  case Added, Removed, Changed, Unchanged
}

enum WarningStatus derives ReadWriter {
  case New, Existing, Resolved
}

enum EdgeKind derives ReadWriter {
  case Wiring, Reference, TableUse, Publishes, Subscribes, Event
}

final case class Edge(
  kind: EdgeKind,
  from: String,
  to: String,
  label: String)
    derives ReadWriter

@serializeDefaults(true)
final case class ComponentDiff(
  name: String,
  status: Status,
  base: Option[Component],
  head: Option[Component],
  codeChanged: Boolean = false)
    derives ReadWriter

enum MemberSection derives ReadWriter {
  case Consumes, Provides, Contributes
}

final case class MemberDiff(
  section: MemberSection,
  name: String,
  text: String,
  status: Status,
  loc: Loc)
    derives ReadWriter

@serializeDefaults(true)
final case class ModuleDiff(
  id: String,
  status: Status,
  base: Option[Module],
  head: Option[Module],
  components: List[ComponentDiff],
  members: List[MemberDiff],
  changedFiles: List[String] = Nil)
    derives ReadWriter

@serializeDefaults(true)
final case class NodeDiff[A](
  key: String,
  status: Status,
  base: Option[A],
  head: Option[A],
  codeChanged: Boolean = false)
    derives ReadWriter

final case class EdgeDiff(edge: Edge, status: Status) derives ReadWriter

final case class WarningDiff(warning: Warning, status: WarningStatus) derives ReadWriter

final case class UnclassifiedDiff(item: Unclassified, status: WarningStatus) derives ReadWriter

@serializeDefaults(true)
final case class DiffModel(
  isDiff: Boolean,
  baseLabel: String,
  headLabel: String,
  modules: List[ModuleDiff],
  tables: List[NodeDiff[TableDecl]],
  topics: List[NodeDiff[BusTopic]],
  events: List[NodeDiff[OutboxEvent]],
  tasks: List[NodeDiff[TaskDecl]],
  edges: List[EdgeDiff],
  references: List[NodeDiff[Reference]],
  wirings: List[NodeDiff[Wiring]],
  warnings: List[WarningDiff],
  unclassified: List[UnclassifiedDiff],
  metrics: MetricsDiff,
  migrations: List[NodeDiff[Migration]] = Nil,
  changedOutside: List[String] = Nil)
    derives ReadWriter

object ArchDiff {
  def full(model: ArchModel, label: String): DiffModel = compare(model, model, label, label).copy(isDiff = false)

  def compare(
    base: ArchModel,
    head: ArchModel,
    baseLabel: String,
    headLabel: String
  ): DiffModel = {
    val modules = keyed(base.modules, head.modules)(_.id).map {
      (
        id,
        before,
        after
      ) =>
        ModuleDiff(
          id,
          status(before.map(moduleFacts(_, base)), after.map(moduleFacts(_, head)))(identity),
          before,
          after,
          components(before, after, changedFiles(before, after)),
          members(before, after),
          changedFiles(before, after)
        )
    }
    val changedOutside = changedDigests(base.outsideModules, head.outsideModules)
    val changedPaths   = (modules.flatMap(_.changedFiles) ++ changedOutside).toSet
    val migrations     = MigrationExtractor.inVersionOrder(nodes(base.migrations, head.migrations)(_.path)(_.digest))(_.key)
    val migrated       = migrations.filter(_.status != Status.Unchanged).flatMap(m => (m.base.toList ++ m.head.toList).flatMap(_.tables)).toSet
    DiffModel(
      isDiff = true,
      baseLabel = baseLabel,
      headLabel = headLabel,
      modules = modules,
      tables = nodes(base.tables, head.tables)(_.name)(t => (t.owner, sites(t.users))).map(t =>
        t.copy(codeChanged = t.status == Status.Unchanged && (columnChanges(t).nonEmpty || migrated(t.key)))
      ),
      topics = nodes(base.topics, head.topics)(_.name)(t => (t.eventType, t.owner, sites(t.publishers), sites(t.subscribers)))
        .map(withCodeChange(_, changedPaths)(_.definedIn)),
      events = nodes(base.outboxEvents, head.outboxEvents)(_.eventType)(e =>
        (e.typeName, e.owner, sites(e.publishers), e.subscribers.map(s => (s.consumer, s.site.owner, s.site.enclosing)).toSet)
      ).map(withCodeChange(_, changedPaths)(_.definedIn)),
      tasks =
        nodes(numbered(base.tasks)(taskKey), numbered(head.tasks)(taskKey))(_._1)((_, t) => (t.kind, t.schedule, t.owner, sites(t.scheduledFrom)))
          .map(t => NodeDiff(t.key, t.status, t.base.map(_._2), t.head.map(_._2))),
      edges = edgeDiffs(edges(base), edges(head)),
      references = nodes(base.references, head.references)(r => s"${r.from}->${r.to}:${r.layer}.${r.symbol}")(_ => ()),
      wirings = nodes(base.wirings, head.wirings)(w => s"${w.consumer}:${w.member}:${w.provider}")(_ => ()),
      warnings = keyed(base.warnings, head.warnings)(_.key).flatMap {
        (
          _,
          before,
          after
        ) =>
          after.map(WarningDiff(_, presence(before))).orElse(before.map(WarningDiff(_, WarningStatus.Resolved)))
      },
      unclassified = keyed(base.unclassified, head.unclassified)(_.key).flatMap {
        (
          _,
          before,
          after
        ) =>
          after.map(UnclassifiedDiff(_, presence(before))).orElse(before.map(UnclassifiedDiff(_, WarningStatus.Resolved)))
      },
      metrics = ArchMetrics.diff(base, head),
      migrations = migrations,
      changedOutside = changedOutside
    )
  }

  def columnChanges(table: NodeDiff[TableDecl]): List[(Status, List[String])] = {
    val was   = table.base.toList.flatMap(_.columns).map(c => c.name -> c.codec).toMap
    val is    = table.head.toList.flatMap(_.columns).map(c => c.name -> c.codec).toMap
    val names = (was.keySet ++ is.keySet).toList.sorted
    List(
      Status.Added   -> names.filter(name => !was.contains(name)),
      Status.Changed -> names.filter(name => was.contains(name) && is.contains(name) && was(name) != is(name)),
      Status.Removed -> names.filter(name => !is.contains(name))
    ).filter(_._2.nonEmpty)
  }

  def changedPathOf(file: String, changedPaths: Iterable[String]): Option[String] = changedPaths.find(_ == file)

  private def withCodeChange[A](node: NodeDiff[A], changedPaths: Set[String])(definedIn: A => Option[String]): NodeDiff[A] =
    node.copy(codeChanged = node.status == Status.Unchanged && node.head.flatMap(definedIn).exists(changedPathOf(_, changedPaths).isDefined))

  def isUse(reference: Reference): Boolean = Layers.Internal(reference.layer) && reference.explainedBy.isEmpty

  def referenceLabel(reference: Reference): String = if (isUse(reference)) s"${reference.layer}.${reference.symbol}" else reference.layer

  def edges(model: ArchModel): Set[Edge] = {
    val wirings = model.wirings.collect { case Wiring(member, consumer, Provider.FromModule(provider)) =>
      Edge(EdgeKind.Wiring, s"module:$consumer", s"module:$provider", member)
    }
    val references = model.references.map(r => Edge(EdgeKind.Reference, s"module:${r.from}", s"module:${r.to}", referenceLabel(r)))
    val tableUses  = model.tables.flatMap(t => moduleIds(t.users).map(id => Edge(EdgeKind.TableUse, s"module:$id", s"table:${t.name}", "")))
    val topics     = model.topics.flatMap { t =>
      moduleIds(t.publishers).map(id => Edge(EdgeKind.Publishes, s"module:$id", s"topic:${t.name}", "")) ++
        moduleIds(t.subscribers).map(id => Edge(EdgeKind.Subscribes, s"topic:${t.name}", s"module:$id", ""))
    }
    val events = model.outboxEvents.flatMap { e =>
      moduleIds(e.publishers).map(id => Edge(EdgeKind.Publishes, s"module:$id", s"event:${e.eventType}", "")) ++
        e.subscribers.flatMap(s => moduleId(s.site.owner).map(id => Edge(EdgeKind.Subscribes, s"event:${e.eventType}", s"module:$id", s.consumer)))
    }
    val flows = model.topics.map(t => (t.name, moduleIds(t.publishers), moduleIds(t.subscribers))) ++
      model.outboxEvents.map(e => (e.eventType, moduleIds(e.publishers), e.subscribers.flatMap(s => moduleId(s.site.owner)).distinct))
    val eventFlows = flows.flatMap {
      (
        name,
        publishers,
        subscribers
      ) =>
        for {
          publisher  <- publishers
          subscriber <- subscribers if subscriber != publisher
        } yield Edge(EdgeKind.Event, s"module:$publisher", s"module:$subscriber", name)
    }
    (wirings ++ references ++ tableUses ++ topics ++ events ++ eventFlows).toSet
  }

  private final case class ModuleFacts(
    mixedIn: Boolean,
    consumes: Set[(String, String)],
    provides: Set[(String, String)],
    contributions: Set[(Registry, List[String])],
    components: Set[(String, ComponentKind, List[String])],
    tables: Set[String],
    topics: Set[String],
    events: Set[String],
    tasks: List[(String, Int, Option[String])],
    references: Set[(String, String, String)])

  private def presence(before: Option[?]): WarningStatus = if (before.isDefined) WarningStatus.Existing else WarningStatus.New

  private def moduleId(owner: Owner): Option[String] = owner match {
    case Owner.Module(id) => Some(id)
    case _                => None
  }

  private def moduleIds(sites: List[Site]): List[String] = sites.flatMap(site => moduleId(site.owner)).distinct

  private def sites(list: List[Site]): Set[(Owner, String)] = list.map(site => (site.owner, site.enclosing)).toSet

  private def taskKey(task: TaskDecl): String = s"${task.owner.label}:${task.name}"

  private def numbered[A](items: List[A])(key: A => String): List[(String, A)] =
    items.zipWithIndex.map { (item, index) =>
      val earlier = items.take(index).count(key(_) == key(item))
      (if (earlier == 0) key(item) else s"${key(item)}#${earlier + 1}") -> item
    }

  private def keyed[A](base: List[A], head: List[A])(key: A => String): List[(String, Option[A], Option[A])] =
    (base.map(key) ++ head.map(key)).distinct.sorted.map(k => (k, base.find(key(_) == k), head.find(key(_) == k)))

  private def status[A, F](before: Option[A], after: Option[A])(facts: A => F): Status = (before, after) match {
    case (None, Some(_))    => Status.Added
    case (Some(_), None)    => Status.Removed
    case (Some(b), Some(a)) => if (facts(b) == facts(a)) Status.Unchanged else Status.Changed
    case (None, None)       => Status.Unchanged
  }

  private def nodes[A, F](base: List[A], head: List[A])(key: A => String)(facts: A => F): List[NodeDiff[A]] =
    keyed(base, head)(key).map(
      (
        k,
        before,
        after
      ) => NodeDiff(k, status(before, after)(facts), before, after)
    )

  private def changedFiles(before: Option[Module], after: Option[Module]): List[String] = (before, after) match {
    case (Some(b), Some(a)) => changedDigests(b.files, a.files)
    case _                  => Nil
  }

  private def changedDigests(base: List[FileDigest], head: List[FileDigest]): List[String] = {
    val was = base.map(f => f.path -> f.digest).toMap
    val is  = head.map(f => f.path -> f.digest).toMap
    (was.keySet ++ is.keySet).filter(path => was.get(path) != is.get(path)).toList.sorted
  }

  private def components(
    before: Option[Module],
    after: Option[Module],
    changedFiles: List[String]
  ): List[ComponentDiff] =
    keyed(before.toList.flatMap(_.components), after.toList.flatMap(_.components))(_.name).map {
      (
        name,
        b,
        a
      ) =>
        val componentStatus = status(b, a)(c => (c.kind, c.dependencies))
        val fileChanged     = a.exists(component => changedFiles.contains(component.file))
        ComponentDiff(name, componentStatus, b, a, codeChanged = componentStatus == Status.Unchanged && fileChanged)
    }

  private def moduleFacts(module: Module, model: ArchModel): ModuleFacts = {
    val owner = Owner.Module(module.id)
    ModuleFacts(
      module.mixedIn,
      module.consumes.map(m => (m.name, m.tpe)).toSet,
      module.provides.map(m => (m.name, m.tpe)).toSet,
      module.contributions.map(c => (c.registry, c.items)).toSet,
      module.components.map(c => (c.name, c.kind, c.dependencies)).toSet,
      model.tables.filter(_.owner == owner).map(_.name).toSet,
      model.topics.filter(_.owner == owner).map(_.name).toSet,
      model.outboxEvents.filter(_.owner == owner).map(_.eventType).toSet,
      model.tasks.filter(_.owner == owner).map(t => (t.name, t.kind.ordinal, t.schedule)).sorted,
      model.references.filter(_.from == module.id).map(r => (r.to, r.layer, r.symbol)).toSet
    )
  }

  private def members(before: Option[Module], after: Option[Module]): List[MemberDiff] = {
    val baseEntries = memberEntries(before)
    val headEntries = memberEntries(after)
    keyed(baseEntries, headEntries)(m => s"${m.section}:${m.text}")
      .flatMap {
        (
          _,
          b,
          a
        ) =>
          a.map(_.copy(status = if (b.isDefined) Status.Unchanged else Status.Added)).orElse(b.map(_.copy(status = Status.Removed)))
      }
      .sortBy(m => (m.section.ordinal, m.text))
  }

  private def memberEntries(module: Option[Module]): List[MemberDiff] =
    module.toList.flatMap { m =>
      m.consumes.map(c => MemberDiff(MemberSection.Consumes, c.name, memberText(c), Status.Unchanged, c.loc)) ++
        m.provides.map(p => MemberDiff(MemberSection.Provides, p.name, memberText(p), Status.Unchanged, p.loc)) ++
        m.contributions.map(c =>
          MemberDiff(MemberSection.Contributes, c.registry.toString, s"${c.registry}: ${c.items.mkString(", ")}", Status.Unchanged, c.loc)
        )
    }

  private def memberText(member: Member): String = if (member.tpe.isEmpty) member.name else s"${member.name}: ${member.tpe}"

  private def edgeDiffs(base: Set[Edge], head: Set[Edge]): List[EdgeDiff] =
    (base ++ head).toList
      .sortBy(e => (e.kind.ordinal, e.from, e.to, e.label))
      .map(edge => EdgeDiff(edge, if (base(edge) && head(edge)) Status.Unchanged else if (head(edge)) Status.Added else Status.Removed))
}
