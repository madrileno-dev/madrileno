package architecture

enum NodeKind {
  case Module, Component, Table, Topic, Event
}

final case class GNode(
  key: String,
  label: String,
  kind: NodeKind,
  parent: Option[String],
  status: Status,
  codeChanged: Boolean = false)

final case class GEdge(
  from: String,
  to: String,
  label: String,
  status: Status,
  repositories: Boolean,
  event: Boolean = false)

enum Level {
  case Modules, Components, Everything
}

final case class Graph(
  nodes: List[GNode],
  edges: List[GEdge],
  collapsed: Boolean)

object GraphView {
  val MaxNodes: Int = 30

  val MaxDiagramNodes: Int = 80

  val MaxDiagramEdges: Int = 300

  val MaxDiagramText: Int = 50000

  def shown(diff: DiffModel): List[ModuleDiff] =
    diff.modules.filter(m => !m.id.startsWith("utils/") || (diff.isDiff && (m.status != Status.Unchanged || m.changedFiles.nonEmpty)))

  def changes(
    diff: DiffModel,
    level: Level = Level.Everything,
    collapse: Boolean = true
  ): Graph = {
    val atLevel    = (key: String) => level == Level.Everything || key.startsWith("module:")
    val top        = topLevel(diff).filter(node => atLevel(node.key))
    val graphEdges = diff.edges.filter(e => atLevel(e.edge.from) && atLevel(e.edge.to) && GraphView.atLevel(level)(e))
    val changed    = top.filter(_.status != Status.Unchanged).map(_.key).toSet
    val touched    = graphEdges.filter(_.status != Status.Unchanged).flatMap(e => List(e.edge.from, e.edge.to)).toSet
    val neighbours = graphEdges.flatMap { e =>
      List(e.edge.from -> e.edge.to, e.edge.to -> e.edge.from).collect { case (node, other) if changed(node) => other }
    }.toSet
    val codeOnly   = top.filter(_.codeChanged).map(_.key).toSet
    val included   = changed ++ touched ++ neighbours ++ codeOnly
    val nodes      = top.filter(node => included(node.key))
    val visible    = top.map(_.key).toSet
    val components =
      if (level == Level.Modules) Nil
      else
        shown(diff)
          .filter(m => included(s"module:${m.id}"))
          .flatMap(m => m.components.filter(c => c.status != Status.Unchanged || c.codeChanged).map(componentNode(m.id, _)))
    val edges = mergedEdges(
      graphEdges.filter(e =>
        visible(e.edge.from) && visible(e.edge.to) && included(e.edge.from) && included(e.edge.to) &&
          (e.status != Status.Unchanged || changed(e.edge.from) || changed(e.edge.to))
      ),
      diff.references
    )
    if (collapse && nodes.size + components.size > MaxNodes) Graph(nodes, edges, collapsed = true)
    else Graph(nodes ++ components, edges, collapsed = false)
  }

  def full(diff: DiffModel, level: Level): Graph = {
    val top        = topLevel(diff).filter(node => node.kind == NodeKind.Module || level == Level.Everything)
    val keys       = top.map(_.key).toSet
    val components = if (level == Level.Modules) Nil else shown(diff).flatMap(m => m.components.map(componentNode(m.id, _)))
    val edges      = mergedEdges(diff.edges.filter(e => keys(e.edge.from) && keys(e.edge.to) && atLevel(level)(e)), diff.references)
    Graph(top ++ components, edges, collapsed = false)
  }

  def focus(
    diff: DiffModel,
    moduleId: String,
    level: Level
  ): Graph = {
    val graph   = full(diff, level)
    val center  = s"module:$moduleId"
    val shared  = graph.nodes.filter(_.kind != NodeKind.Module).map(_.key).toSet
    val direct  = graph.edges.filter(e => e.from == center || e.to == center)
    val through = direct.flatMap(e => List(e.from, e.to)).filter(shared).toSet
    val edges   = graph.edges.filter(e => e.from == center || e.to == center || through(e.from) || through(e.to))
    val keys    = edges.flatMap(e => List(e.from, e.to)).toSet + center
    Graph(graph.nodes.filter(node => keys(node.key) || node.parent.contains(center)), edges, collapsed = false)
  }

  private def atLevel(level: Level)(edge: EdgeDiff): Boolean = level != Level.Everything || edge.edge.kind != EdgeKind.Event

  private def topLevel(diff: DiffModel): List[GNode] = {
    val shownKeys = shown(diff).map(m => s"module:${m.id}").toSet
    val drawn     = (key: String, owner: Option[Owner]) =>
      owner.exists {
        case Owner.Module(id) => shownKeys(s"module:$id")
        case _                => false
      } || diff.edges.exists(e => (e.edge.from == key && shownKeys(e.edge.to)) || (e.edge.to == key && shownKeys(e.edge.from)))
    val shared =
      diff.tables.map(t => GNode(s"table:${t.key}", t.key, NodeKind.Table, None, t.status, t.codeChanged) -> t.head.orElse(t.base).map(_.owner)) ++
        diff.topics.map(t => GNode(s"topic:${t.key}", t.key, NodeKind.Topic, None, t.status, t.codeChanged) -> t.head.orElse(t.base).map(_.owner)) ++
        diff.events.map(e =>
          GNode(s"event:${e.key}", e.head.orElse(e.base).map(_.typeName).getOrElse(e.key), NodeKind.Event, None, e.status, e.codeChanged) ->
            e.head.orElse(e.base).map(_.owner)
        )
    shown(diff).map(m =>
      GNode(s"module:${m.id}", m.id, NodeKind.Module, None, m.status, codeChanged = m.status == Status.Unchanged && m.changedFiles.nonEmpty)
    ) ++ shared.collect { case (node, owner) if drawn(node.key, owner) => node }
  }

  private def componentNode(moduleId: String, component: ComponentDiff): GNode = {
    val kind = component.head.orElse(component.base).map(_.kind.toString.toLowerCase).getOrElse("other")
    GNode(
      s"component:$moduleId:${component.name}",
      s"${component.name} ($kind)",
      NodeKind.Component,
      Some(s"module:$moduleId"),
      component.status,
      component.codeChanged
    )
  }

  private val MaxNamedTypes = 3

  private def mergedEdges(edges: List[EdgeDiff], references: List[NodeDiff[Reference]]): List[GEdge] =
    edges
      .groupBy(e => (e.edge.from, e.edge.to, e.status))
      .toList
      .sortBy { case ((from, to, status), _) => (from, to, status.ordinal) }
      .map { case ((from, to, status), group) =>
        val between = referencesOf(references, from, to, status)
        GEdge(
          from,
          to,
          labelOf(group, between),
          status,
          group.exists(e => e.edge.kind == EdgeKind.Reference && e.edge.label == "repositories") &&
            between.exists(r => r.layer == "repositories" && r.explainedBy.isEmpty),
          group.forall(_.edge.kind == EdgeKind.Event)
        )
      }

  private def referencesOf(
    references: List[NodeDiff[Reference]],
    from: String,
    to: String,
    status: Status
  ): List[Reference] =
    references
      .flatMap(node => if (status == Status.Removed) node.base else node.head)
      .filter(r => s"module:${r.from}" == from && s"module:${r.to}" == to)

  private def labelOf(group: List[EdgeDiff], references: List[Reference]): String = {
    def labels(kind: EdgeKind): List[String] = group.filter(_.edge.kind == kind).map(_.edge.label).filter(_.nonEmpty).distinct
    val consumers                            = group
      .filter(e => e.edge.kind == EdgeKind.Subscribes && e.edge.label.nonEmpty && s"module:${e.edge.label}" != e.edge.to)
      .map(e => s"as ${e.edge.label}")
      .distinct
    val layers  = labels(EdgeKind.Reference).toSet
    val inGroup = references.filter(r => layers(r.layer))
    val imports = inGroup.filterNot(r => Layers.Internal(r.layer)).map(_.layer).distinct.sorted
    val uses    = inGroup
      .filter(r => Layers.Internal(r.layer) && r.explainedBy.isEmpty)
      .map(r => if (r.symbol == "*" || r.symbol == "given") s"${r.layer}.${r.symbol}" else r.symbol)
      .distinct
      .sorted
    val named = (names: List[String]) =>
      if (names.size > MaxNamedTypes) s"${names.take(MaxNamedTypes).mkString(", ")}, +${names.size - MaxNamedTypes} more"
      else names.mkString(", ")
    val events = labels(EdgeKind.Event).sorted
    List(
      Option.when(labels(EdgeKind.Wiring).nonEmpty)(s"wires ${labels(EdgeKind.Wiring).mkString(", ")}"),
      Option.when(imports.nonEmpty)(s"imports ${imports.mkString(", ")}"),
      Option.when(uses.nonEmpty)(s"uses ${named(uses)}"),
      Option.when(events.nonEmpty)(s"event ${named(events)}")
    ).flatten.++(consumers).mkString(" · ")
  }
}
