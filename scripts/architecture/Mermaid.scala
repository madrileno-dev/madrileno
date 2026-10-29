package architecture

object Mermaid {
  private final case class Palette(
    fill: String,
    stroke: String,
    extra: String)

  private val palettes = Map(
    Status.Added   -> Palette("#2f7d4a", "#1f5c35", ""),
    Status.Removed -> Palette("#b5122b", "#7a0c1d", ",stroke-dasharray:4 3"),
    Status.Changed -> Palette("#a8660b", "#7a4a08", "")
  )

  private val Theme: String =
    List(
      "---",
      "config:",
      "  theme: base",
      "  themeVariables:",
      "    fontFamily: \"Manrope, ui-sans-serif, system-ui, sans-serif\"",
      "    primaryColor: \"#fffdf9\"",
      "    primaryBorderColor: \"#cfc9be\"",
      "    primaryTextColor: \"#1e1e24\"",
      "    lineColor: \"#5c5b63\"",
      "    clusterBkg: \"#f5f1ea\"",
      "    clusterBorder: \"#cfc9be\"",
      "    edgeLabelBackground: \"#f5f1ea\"",
      "    tertiaryColor: \"#f5f1ea\""
    ).mkString("", "\n", "\n")

  private val Credit: String = "\n<sub>Mapped by Madrileño's <code>scripts/architecture.scala</code></sub>\n"

  def render(diff: DiffModel): String = {
    val graph  = GraphView.changes(diff)
    val footer = footerOf(diff)
    if (graph.nodes.isEmpty) s"No architecture changes between `${diff.baseLabel}` and `${diff.headLabel}`.\n$footer"
    else if (graph.nodes.size > GraphView.MaxDiagramNodes || graph.edges.size > GraphView.MaxDiagramEdges) {
      val intro =
        s"_The diagram would be too large for GitHub to render (${graph.nodes.size} boxes, ${graph.edges.size} arrows). Open the HTML for the full picture._"
      s"### Architecture changes: `${diff.baseLabel}` → `${diff.headLabel}`\n\n$intro\n\n${summary(diff)}$footer$Credit"
    } else {
      val note =
        if (graph.collapsed)
          s"\n_Services and repositories are hidden: the diagram would have more than ${GraphView.MaxNodes} boxes. Open the HTML for details._\n"
        else ""
      s"### Architecture changes: `${diff.baseLabel}` → `${diff.headLabel}`\n\n```mermaid\n$Theme---\nflowchart LR\n${body(graph)}```\n$note$footer$Credit"
    }
  }

  def nodeLine(node: GNode): String = {
    val id    = idOf(node.key)
    val label = s"${prefix(node.status)}${escape(node.label)}"
    node.kind match {
      case NodeKind.Table                       => s"""$id[("$label")]"""
      case NodeKind.Topic                       => s"""$id>"$label"]"""
      case NodeKind.Event                       => s"""$id{{"$label"}}"""
      case NodeKind.Platform                    => s"""$id(["$label"])"""
      case NodeKind.Module | NodeKind.Component => s"""$id["$label"]"""
    }
  }

  private def body(graph: Graph): String = {
    val children  = graph.nodes.filter(_.parent.isDefined).groupBy(_.parent.getOrElse(""))
    val subgraphs = graph.nodes.filter(node => node.parent.isEmpty && children.contains(node.key))
    val nodeLines = graph.nodes.filter(_.parent.isEmpty).flatMap { node =>
      children.get(node.key) match {
        case Some(inner) =>
          List(s"""  subgraph ${idOf(node.key)}["${prefix(node.status)}${escape(node.label)}"]""", "    direction LR") ++
            inner.map(child => s"    ${nodeLine(child)}") ++ List("  end")
        case None => List(s"  ${nodeLine(node)}")
      }
    }
    val edgeLines  = graph.edges.map(edgeLine)
    val classLines = graph.nodes.filter(_.status != Status.Unchanged).map { node =>
      if (subgraphs.contains(node)) {
        val palette = palettes(node.status)
        s"  style ${idOf(node.key)} fill:${palette.fill},stroke:${palette.stroke},color:#ffffff${palette.extra}"
      } else s"  class ${idOf(node.key)} ${node.status.toString.toLowerCase}"
    }
    val linkStyles = graph.edges.zipWithIndex.collect {
      case (edge, index) if edge.status == Status.Added   => s"  linkStyle $index stroke:#2f7d4a,stroke-width:2px"
      case (edge, index) if edge.status == Status.Removed => s"  linkStyle $index stroke:#b5122b"
    }
    val classDefs = List(Status.Added, Status.Removed, Status.Changed).map { status =>
      val palette = palettes(status)
      s"  classDef ${status.toString.toLowerCase} fill:${palette.fill},stroke:${palette.stroke},color:#ffffff${palette.extra}"
    }
    (classDefs ++ nodeLines ++ edgeLines ++ classLines ++ linkStyles).map(_ + "\n").mkString
  }

  private def edgeLine(edge: GEdge): String = {
    val arrow =
      if (edge.status == Status.Removed) "-.->"
      else if (edge.repositories) "==>"
      else "-->"
    val label = if (edge.label.isEmpty) "" else s"|${escape(edge.label).replace("|", "#124;")}|"
    s"  ${idOf(edge.from)} $arrow$label ${idOf(edge.to)}"
  }

  private def summary(diff: DiffModel): String = {
    val groups = List(
      "modules" -> diff.modules.map(m => m.id -> m.status),
      "tables"  -> diff.tables.map(t => t.key -> t.status),
      "topics"  -> diff.topics.map(t => t.key -> t.status),
      "events"  -> diff.events.map(e => e.head.orElse(e.base).map(_.typeName).getOrElse(e.key) -> e.status)
    )
    val lines = for {
      status          <- List(Status.Added, Status.Removed, Status.Changed)
      (kind, entries) <- groups
      names = entries.collect { case (name, `status`) => name }
      if names.nonEmpty
    } yield s"- **$status $kind (${names.size}):** ${names.mkString(", ")}"
    lines.mkString("", "\n", "\n")
  }

  private def footerOf(diff: DiffModel): String = {
    val warnings =
      diff.warnings.filter(_.status == WarningStatus.New).map(w => s"- `${w.warning.loc.path}:${w.warning.loc.line}` — ${w.warning.message}")
    val unclassified =
      diff.unclassified.filter(_.status == WarningStatus.New).map(u => s"- `${u.item.loc.path}:${u.item.loc.line}` — ${u.item.description}")
    section("New warnings", warnings) + section("New unclassified", unclassified)
  }

  private def section(title: String, lines: List[String]): String =
    if (lines.isEmpty) "" else s"\n**$title**\n\n${lines.mkString("\n")}\n"

  private def idOf(key: String): String = key.replaceAll("[^A-Za-z0-9]", "_")

  private def prefix(status: Status): String = status match {
    case Status.Added     => "#43;#160;"
    case Status.Removed   => "−#160;"
    case Status.Changed   => "~#160;"
    case Status.Unchanged => ""
  }

  private def escape(text: String): String = text.replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;")
}
