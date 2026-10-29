package architecture

enum NodeKind {
  case Module, Component, Table, Topic, Event, Platform
}

final case class GNode(
  key: String,
  label: String,
  kind: NodeKind,
  parent: Option[String],
  status: Status)

final case class GEdge(
  from: String,
  to: String,
  label: String,
  status: Status,
  repositories: Boolean)

final case class Graph(
  nodes: List[GNode],
  edges: List[GEdge],
  collapsed: Boolean)

object GraphView {
  val MaxNodes: Int = 30

  val MaxDiagramNodes: Int = 80

  val MaxDiagramEdges: Int = 300

  def changes(diff: DiffModel): Graph = {
    val top        = topLevel(diff)
    val changed    = top.filter(_.status != Status.Unchanged).map(_.key).toSet
    val touched    = diff.edges.filter(_.status != Status.Unchanged).flatMap(e => List(e.edge.from, e.edge.to)).toSet
    val neighbours = diff.edges.flatMap { e =>
      List(e.edge.from -> e.edge.to, e.edge.to -> e.edge.from).collect { case (node, other) if changed(node) => other }
    }.toSet
    val included   = changed ++ touched ++ neighbours
    val nodes      = top.filter(node => included(node.key))
    val components = diff.modules
      .filter(m => included(s"module:${m.id}"))
      .flatMap(m => m.components.filter(_.status != Status.Unchanged).map(componentNode(m.id, _)))
    val edges = mergedEdges(
      diff.edges.filter(e =>
        included(e.edge.from) && included(e.edge.to) && (e.status != Status.Unchanged || changed(e.edge.from) || changed(e.edge.to))
      )
    )
    if (nodes.size + components.size > MaxNodes) Graph(nodes, edges, collapsed = true)
    else Graph(nodes ++ components, edges, collapsed = false)
  }

  def modules(diff: DiffModel): Graph = {
    val nodes = diff.modules.map(m => GNode(s"module:${m.id}", m.id, NodeKind.Module, None, m.status))
    val edges = mergedEdges(diff.edges.filter(e => e.edge.from.startsWith("module:") && e.edge.to.startsWith("module:")))
    Graph(nodes, edges, collapsed = false)
  }

  private def topLevel(diff: DiffModel): List[GNode] =
    diff.modules.map(m => GNode(s"module:${m.id}", m.id, NodeKind.Module, None, m.status)) ++
      diff.tables.map(t => GNode(s"table:${t.key}", t.key, NodeKind.Table, None, t.status)) ++
      diff.topics.map(t => GNode(s"topic:${t.key}", t.key, NodeKind.Topic, None, t.status)) ++
      diff.events.map(e => GNode(s"event:${e.key}", e.head.orElse(e.base).map(_.typeName).getOrElse(e.key), NodeKind.Event, None, e.status))

  private def componentNode(moduleId: String, component: ComponentDiff): GNode = {
    val kind = component.head.orElse(component.base).map(_.kind.toString.toLowerCase).getOrElse("other")
    GNode(s"component:$moduleId:${component.name}", s"${component.name} ($kind)", NodeKind.Component, Some(s"module:$moduleId"), component.status)
  }

  private def mergedEdges(edges: List[EdgeDiff]): List[GEdge] =
    edges
      .groupBy(e => (e.edge.from, e.edge.to, e.status))
      .toList
      .sortBy { case ((from, to, status), _) => (from, to, status.ordinal) }
      .map { case ((from, to, status), group) =>
        GEdge(
          from,
          to,
          group.map(_.edge.label).filter(_.nonEmpty).distinct.mkString(", "),
          status,
          group.exists(e => e.edge.kind == EdgeKind.Reference && e.edge.label == "repositories")
        )
      }
}
