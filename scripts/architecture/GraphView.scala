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
      ),
      diff.references
    )
    if (nodes.size + components.size > MaxNodes) Graph(nodes, edges, collapsed = true)
    else Graph(nodes ++ components, edges, collapsed = false)
  }

  def modules(diff: DiffModel): Graph = {
    val nodes = diff.modules.map(m => GNode(s"module:${m.id}", m.id, NodeKind.Module, None, m.status))
    val edges = mergedEdges(diff.edges.filter(e => e.edge.from.startsWith("module:") && e.edge.to.startsWith("module:")), diff.references)
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

  private val InternalLayers = Set("services", "repositories", "routers", "gateways")

  private val MaxNamedTypes = 3

  private def mergedEdges(edges: List[EdgeDiff], references: List[NodeDiff[Reference]]): List[GEdge] =
    edges
      .groupBy(e => (e.edge.from, e.edge.to, e.status))
      .toList
      .sortBy { case ((from, to, status), _) => (from, to, status.ordinal) }
      .map { case ((from, to, status), group) =>
        GEdge(
          from,
          to,
          labelOf(group, referencesOf(references, from, to, status)),
          status,
          group.exists(e => e.edge.kind == EdgeKind.Reference && e.edge.label == "repositories")
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
    val imports = inGroup.filterNot(r => InternalLayers(r.layer)).map(_.layer).distinct.sorted
    val uses    = inGroup
      .filter(r => InternalLayers(r.layer) && r.explainedBy.isEmpty)
      .map(r => if (r.symbol == "*") s"${r.layer}.*" else r.symbol)
      .distinct
      .sorted
    val named =
      if (uses.size > MaxNamedTypes) s"${uses.take(MaxNamedTypes).mkString(", ")}, +${uses.size - MaxNamedTypes} more"
      else uses.mkString(", ")
    List(
      Option.when(labels(EdgeKind.Wiring).nonEmpty)(s"wires ${labels(EdgeKind.Wiring).mkString(", ")}"),
      Option.when(imports.nonEmpty)(s"imports ${imports.mkString(", ")}"),
      Option.when(uses.nonEmpty)(s"uses $named")
    ).flatten.++(consumers).mkString(" · ")
  }
}
