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
    val graph     = GraphView.changes(diff)
    val outside   = undrawn(diff, graph)
    val footer    = section("Outside the modules", outside) + footerOf(diff)
    val unchanged = graph.nodes.forall(_.status == Status.Unchanged) && graph.edges.forall(_.status == Status.Unchanged)
    if (unchanged && outside.isEmpty)
      s"No architecture changes between `${diff.baseLabel}` and `${diff.headLabel}`.\n${if (metricsMoved(diff)) metricsSection(diff) else ""}$footer$Credit"
    else if (unchanged) s"### Architecture changes: `${diff.baseLabel}` → `${diff.headLabel}`\n${metricsSection(diff)}$footer$Credit"
    else
      tooLarge(graph) match {
        case Some(intro) =>
          s"### Architecture changes: `${diff.baseLabel}` → `${diff.headLabel}`\n\n$intro\n\n${summary(diff)}${metricsSection(diff)}$footer$Credit"
        case None =>
          val note =
            if (graph.collapsed)
              s"\n_The classes modules wire are hidden: the diagram would have more than ${GraphView.MaxNodes} boxes. Open the HTML for details._\n"
            else ""
          s"### Architecture changes: `${diff.baseLabel}` → `${diff.headLabel}`\n\n```mermaid\n${diagram(graph)}```\n$note${metricsSection(diff)}$footer$Credit"
      }
  }

  def diagram(graph: Graph): String = s"$Theme---\nflowchart LR\n${body(graph)}"

  private def tooLarge(graph: Graph): Option[String] = {
    val characters = diagram(graph).length
    Option.when(
      graph.nodes.size > GraphView.MaxDiagramNodes || graph.edges.size > GraphView.MaxDiagramEdges || characters > GraphView.MaxDiagramText
    )(
      s"_The diagram would be too large for GitHub to render (${graph.nodes.size} boxes, ${graph.edges.size} arrows, $characters characters). Open the HTML for the full picture._"
    )
  }

  def renderFull(diff: DiffModel): String = {
    val detailed  = GraphView.full(diff, Level.Components)
    val collapsed = detailed.nodes.size > GraphView.MaxNodes && detailed.nodes.exists(_.kind == NodeKind.Component)
    val graph     = if (collapsed) GraphView.full(diff, Level.Modules) else detailed
    val shown     =
      tooLarge(graph).fold(s"```mermaid\n${diagram(graph)}```\n")(intro =>
        s"$intro\n\n- **Modules (${GraphView.shown(diff).size}):** ${GraphView.shown(diff).map(_.id).mkString(", ")}\n"
      )
    val note =
      if (collapsed)
        s"\n_The classes modules wire are hidden: the diagram would have more than ${GraphView.MaxNodes} boxes. Open the HTML for details._\n"
      else ""
    val metrics =
      s"\n**Architecture metrics:** ${ArchMetrics.rows.map(row => s"${row.name.toLowerCase} ${row.format(row.value(diff.metrics.head))}").mkString(", ")}.\n"
    val findings =
      section("Warnings", diff.warnings.map(w => s"- `${w.warning.loc.path}:${w.warning.loc.line}` — ${w.warning.message}")) +
        section("Unclassified", diff.unclassified.map(u => s"- `${u.item.loc.path}:${u.item.loc.line}` — ${u.item.description}"))
    s"### Architecture: `${diff.headLabel}`\n\n$shown$note$metrics$findings$Credit"
  }

  def nodeId(key: String): String = idOf(key)

  def nodeLine(node: GNode): String = {
    val id    = idOf(node.key)
    val label = s"${prefix(node.status)}${escape(node.label)}"
    node.kind match {
      case NodeKind.Table                       => s"""$id[("$label")]"""
      case NodeKind.Topic                       => s"""$id>"$label"]"""
      case NodeKind.Event                       => s"""$id{{"$label"}}"""
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
    val codeLines = graph.nodes.filter(_.codeChanged).map { node =>
      if (subgraphs.contains(node)) s"  style ${idOf(node.key)} $CodeChangedStyle" else s"  class ${idOf(node.key)} touched"
    }
    val linkStyles = graph.edges.zipWithIndex.collect {
      case (edge, index) if edge.status == Status.Added   => s"  linkStyle $index stroke:#2f7d4a,stroke-width:2px"
      case (edge, index) if edge.status == Status.Removed => s"  linkStyle $index stroke:#b5122b"
    }
    val classDefs = List(Status.Added, Status.Removed, Status.Changed).map { status =>
      val palette = palettes(status)
      s"  classDef ${status.toString.toLowerCase} fill:${palette.fill},stroke:${palette.stroke},color:#ffffff${palette.extra}"
    }
    val codeDef = Option.when(codeLines.nonEmpty)(s"  classDef touched $CodeChangedStyle")
    (classDefs ++ codeDef ++ nodeLines ++ edgeLines ++ classLines ++ codeLines ++ linkStyles).map(_ + "\n").mkString
  }

  private def edgeLine(edge: GEdge): String = {
    val arrow =
      if (edge.status == Status.Removed || edge.event) "-.->"
      else if (edge.repositories) "==>"
      else "-->"
    val label = if (edge.label.isEmpty) "" else s"""|"${escape(edge.label).replace("|", "#124;")}"|"""
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

  private def metricsMoved(diff: DiffModel): Boolean =
    ArchMetrics.rows.exists(row => ArchMetrics.trend(row, diff.metrics.base, diff.metrics.head).isDefined)

  private def metricsSection(diff: DiffModel): String = {
    val (base, head) = (diff.metrics.base, diff.metrics.head)
    if (!metricsMoved(diff))
      s"\n**Architecture metrics** unchanged: ${ArchMetrics.rows.map(row => s"${row.name.toLowerCase} ${row.format(row.value(head))}").mkString(", ")}.\n"
    else {
      val rows = ArchMetrics.rows.map { row =>
        val trend = ArchMetrics.trend(row, base, head) match {
          case Some(true)  => "▲ worse"
          case Some(false) => "▼ better"
          case None        => "same"
        }
        s"| ${row.name} | ${row.format(row.value(base))} | ${row.format(row.value(head))} | $trend |"
      }
      s"\n**Architecture metrics** (lower is better)\n\n| Metric | `${diff.baseLabel}` | `${diff.headLabel}` | |\n|---|---:|---:|---|\n${rows.mkString("\n")}\n"
    }
  }

  private def footerOf(diff: DiffModel): String = {
    val warnings =
      diff.warnings.filter(_.status == WarningStatus.New).map(w => s"- `${w.warning.loc.path}:${w.warning.loc.line}` — ${w.warning.message}")
    val unclassified =
      diff.unclassified.filter(_.status == WarningStatus.New).map(u => s"- `${u.item.loc.path}:${u.item.loc.line}` — ${u.item.description}")
    val tasks = diff.tasks.filter(_.status != Status.Unchanged).flatMap { node =>
      node.head.orElse(node.base).map { task =>
        val change = node.status match {
          case Status.Added   => "added"
          case Status.Removed => "removed"
          case _              => "changed"
        }
        val was = for {
          before <- node.base.toList
          after  <- node.head.toList
          change <- taskChanges(before, after)
        } yield change
        s"- $change `${task.name}`: ${task.kind.label}${task.schedule.fold("")(schedule => s", ${scheduleText(schedule)}")}, in ${task.owner.label}" +
          was.map(change => s"; $change").mkString
      }
    }
    val code = diff.modules.filter(m => m.status == Status.Unchanged && m.changedFiles.nonEmpty).map { m =>
      s"- ${m.id}: ${listed(m.changedFiles.map(_.stripPrefix(s"${m.id}/")))}"
    }
    val (main, platform) = diff.changedOutside.partition(_.startsWith("main/"))
    val outside          =
      Option.when(platform.nonEmpty)(s"- platform: ${listed(platform)}").toList ++
        Option.when(main.nonEmpty)(s"- main: ${listed(main.map(_.stripPrefix("main/")))}")
    val changedPaths = diff.modules.flatMap(_.changedFiles) ++ diff.changedOutside
    val payloads     =
      diff.topics
        .filter(_.codeChanged)
        .flatMap(t => t.head.flatMap(_.definedIn).flatMap(ArchDiff.changedPathOf(_, changedPaths)).map(path => s"- topic `${t.key}`: `$path`")) ++
        diff.events
          .filter(_.codeChanged)
          .flatMap(e => e.head.flatMap(_.definedIn).flatMap(ArchDiff.changedPathOf(_, changedPaths)).map(path => s"- event `${e.key}`: `$path`"))
    val changedMigrations = diff.migrations.filter(_.status != Status.Unchanged)
    val tables            = diff.tables.filter(t => t.status == Status.Unchanged || t.status == Status.Changed).flatMap { t =>
      val columns = ArchDiff.columnChanges(t).map { (status, names) =>
        s"${status.toString.toLowerCase} ${if (names.size == 1) "column" else "columns"} ${names.map(name => s"`$name`").mkString(", ")}"
      }
      val touched = changedMigrations.filter(m => (m.base.toList ++ m.head.toList).exists(_.tables.contains(t.key))).map(_.key)
      val in = Option.when(touched.nonEmpty)(s"${if (touched.size == 1) "migration" else "migrations"} ${touched.map(m => s"`$m`").mkString(", ")}")
      Option.when(columns.nonEmpty || in.nonEmpty)(s"- table `${t.key}`: ${(columns ++ in).mkString("; ")}")
    }
    val migrations = changedMigrations.flatMap { node =>
      node.head.orElse(node.base).map { migration =>
        val does = migration.summary(table => s"`$table`")
        s"- ${node.status.toString.toLowerCase} `${migration.path}`${if (does.isEmpty) "" else s": $does"}"
      }
    }
    section("Changed modules", changedModules(diff)) + section("Code changed", code ++ outside ++ payloads ++ tables) + section(
      "Migrations",
      migrations
    ) +
      section("Tasks", tasks) + section("New warnings", warnings) + section("New unclassified", unclassified)
  }

  def undrawn(diff: DiffModel, graph: Graph): List[String] = {
    val drawn                                                               = graph.nodes.map(_.key).toSet
    def lines[A](kind: String, nodes: List[NodeDiff[A]])(owner: A => Owner) =
      nodes.filter(n => n.status != Status.Unchanged && !drawn(s"$kind:${n.key}")).flatMap { n =>
        n.head.orElse(n.base).map(item => s"- ${n.status.toString.toLowerCase} $kind `${n.key}` in ${owner(item).label}")
      }
    lines("table", diff.tables)(_.owner) ++ lines("topic", diff.topics)(_.owner) ++ lines("event", diff.events)(_.owner)
  }

  private def taskChanges(before: TaskDecl, after: TaskDecl): List[String] = {
    val callers  = (task: TaskDecl) => task.scheduledFrom.map(_.enclosing).distinct
    val schedule = Option.when(before.schedule != after.schedule)(s"was ${before.schedule.fold("unscheduled")(scheduleText)}")
    val added    = callers(after).filterNot(callers(before).contains)
    val removed  = callers(before).filterNot(callers(after).contains)
    schedule.toList ++ Option.when(added.nonEmpty)(s"now scheduled from ${added.mkString(", ")}") ++
      Option.when(removed.nonEmpty)(s"no longer scheduled from ${removed.mkString(", ")}")
  }

  private def changedModules(diff: DiffModel): List[String] = {
    val change = (status: Status) =>
      status match {
        case Status.Added   => Some("now")
        case Status.Removed => Some("no longer")
        case _              => None
      }
    diff.modules.filter(_.status == Status.Changed).flatMap { m =>
      val mixedIn = for {
        before <- m.base
        after  <- m.head
        if before.mixedIn != after.mixedIn
      } yield s"is ${if (after.mixedIn) "now" else "no longer"} mixed into ApplicationLoader"
      val components = m.components.flatMap(componentChange)
      val members = m.members.flatMap(member => change(member.status).map(when => s"$when ${member.section.toString.toLowerCase} `${member.text}`"))
      val references = diff.references.flatMap { node =>
        node.head.orElse(node.base).filter(_.from == m.id).flatMap { r =>
          change(node.status).map { when =>
            val what = r.symbol match {
              case "*"     => "everything from"
              case "given" => "the givens of"
              case symbol  => s"`$symbol` from"
            }
            s"$when ${if (ArchDiff.isUse(r)) "uses" else "imports"} $what ${r.to}${if (r.layer == "module") "" else s" ${r.layer}"}"
          }
        }
      }
      val lines = mixedIn.toList ++ components ++ members ++ references
      Option.when(lines.nonEmpty)(s"- ${m.id}: ${lines.mkString("; ")}")
    }
  }

  private def componentChange(component: ComponentDiff): Option[String] = {
    val kind = (c: Component) => c.kind.toString.toLowerCase
    (component.status, component.base, component.head) match {
      case (Status.Added, _, Some(after))              => Some(s"now wires `${component.name}` (${kind(after)})")
      case (Status.Removed, Some(_), _)                => Some(s"no longer wires `${component.name}`")
      case (Status.Changed, Some(before), Some(after)) =>
        val takes   = after.dependencies.filterNot(before.dependencies.contains).map(d => s"`$d`")
        val dropped = before.dependencies.filterNot(after.dependencies.contains).map(d => s"`$d`")
        val changes =
          Option.when(takes.nonEmpty)(s"now takes ${takes.mkString(", ")}").toList ++
            Option.when(dropped.nonEmpty)(s"no longer takes ${dropped.mkString(", ")}") ++
            Option.when(before.kind != after.kind)(s"is now a ${kind(after)}")
        Option.when(changes.nonEmpty)(s"`${component.name}` ${changes.mkString(" and ")}")
      case _ => None
    }
  }

  private def listed(paths: List[String]): String = {
    val files = paths.map(path => s"`$path`")
    (files.take(MaxListedFiles) ++ Option.when(files.size > MaxListedFiles)(s"+${files.size - MaxListedFiles} more")).mkString(", ")
  }

  private val MaxListedFiles = 5

  private val CodeChangedStyle = "stroke:#a8660b,stroke-width:2px,stroke-dasharray:3 3"

  private def scheduleText(schedule: String): String = schedule.split(" ", 2) match {
    case Array(kind @ ("cron" | "every" | "after"), value) => s"$kind `$value`"
    case _                                                 => s"`$schedule`"
  }

  private def section(title: String, lines: List[String]): String =
    if (lines.isEmpty) "" else s"\n**$title**\n\n${lines.mkString("\n")}\n"

  private def idOf(key: String): String = {
    val readable = key.replaceAll("[^A-Za-z0-9]", "_")
    if (key.matches("[A-Za-z0-9:]*")) readable else s"${readable}_${Trees.hash(key).take(8)}"
  }

  private def prefix(status: Status): String = status match {
    case Status.Added     => "#43;#160;"
    case Status.Removed   => "−#160;"
    case Status.Changed   => "~#160;"
    case Status.Unchanged => ""
  }

  private def escape(text: String): String =
    text
      .replace("#", "#35;")
      .replace("\"", "#quot;")
      .replace("<", "#lt;")
      .replace(">", "#gt;")
      .replace("*", "#42;")
      .replace("_", "#95;")
      .replace("~", "#126;")
      .replace("`", "#96;")
}
