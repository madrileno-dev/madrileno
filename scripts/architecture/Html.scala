package architecture

object Html {
  val MermaidUrl: String = "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"

  val MermaidIntegrity: String = "sha384-rbtjAdnIQE/aQJGEgXrVUlMibdfTSa4PQju4HDhN3sR2PmaKFzhEafuePsl9H/9I"

  def escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

  def render(diff: DiffModel): String = {
    val title =
      if (diff.isDiff) s"Architecture diff: ${diff.baseLabel} → ${diff.headLabel}"
      else s"Architecture: ${diff.headLabel}"
    val data = upickle.default.write(diff).replace("<", "\\u003c")
    List(
      "<!doctype html>",
      """<html lang="en">""",
      "<head>",
      """<meta charset="utf-8">""",
      """<meta name="viewport" content="width=device-width, initial-scale=1">""",
      s"<title>${escape(title)}</title>",
      s"<style>$styles</style>",
      "</head>",
      "<body>",
      "<header>",
      s"""<div class="brand">$brandMark<span class="eyebrow">Madrileño</span></div>""",
      s"<h1>${escape(title)}</h1>",
      s"""<p class="summary">${escape(summary(diff))}</p>""",
      if (diff.isDiff) """<label class="toggle"><input type="checkbox" id="changed-only"> Changed only</label>""" else "",
      "</header>",
      "<main>",
      s"""<section id="metrics"><h2>Metrics</h2><p class="muted">Lower is better. Read propagation cost together with size concentration: merging modules lowers the first and raises the second.</p>${metricsCards(
          diff
        )}</section>""",
      """<section id="modules"><h2>Modules</h2>""",
      """<div class="graph-controls"><span class="muted">Show</span>""" +
        """<label><input type="radio" name="level" value="modules" checked> modules</label>""" +
        """<label><input type="radio" name="level" value="components"> with services, repositories, routers and gateways</label>""" +
        """<label><input type="radio" name="level" value="everything"> with tables and events</label>""" +
        """<button type="button" id="show-all" hidden>Show all modules</button>""" +
        """<span class="zoom"><button type="button" data-zoom="out" aria-label="Zoom out">−</button><button type="button" data-zoom="fit">Fit</button><button type="button" data-zoom="in" aria-label="Zoom in">+</button></span></div>""",
      if (diff.isDiff) """<p class="muted">Green: added · red: removed · amber: changed · dotted: code changed, architecture didn't.</p>""" else "",
      moduleList(diff),
      """<div class="modules-layout">""",
      """<div class="graph" id="graph"><p class="muted">Drawing the diagram…</p></div>""",
      """<aside id="details"><p class="muted">Click a module to focus the diagram on it and see what it consumes, provides and contributes.</p></aside>""",
      "</div></section>",
      s"""<section id="events"><h2>Events</h2>${eventsTable(diff)}</section>""",
      s"""<section id="tasks"><h2>Tasks</h2>${tasksTable(diff)}</section>""",
      s"""<section id="tables"><h2>Tables</h2>${tablesMatrix(diff)}</section>""",
      s"""<section id="migrations"><h2>Migrations</h2>${migrationsTable(diff)}</section>""",
      if (diff.isDiff) s"""<section id="outside"><h2>Code outside modules</h2>${outsideTable(diff)}</section>""" else "",
      s"""<section id="warnings"><h2>Warnings</h2>${warningsList(diff)}</section>""",
      """<section id="unclassified"><h2>Unclassified</h2>""",
      """<p class="muted">The map reads source, not compiled code. Anything it saw but could not place is listed here instead of being guessed.</p>""",
      s"${unclassifiedList(diff)}</section>",
      "</main>",
      "<footer>Madrileño architecture map · read from source, not compiled code</footer>",
      s"""<script type="application/json" id="arch-data">$data</script>""",
      s"""<script type="application/json" id="arch-diagrams">${diagrams(diff)}</script>""",
      s"""<script src="$MermaidUrl" integrity="$MermaidIntegrity" crossorigin="anonymous"></script>""",
      s"<script>$script</script>",
      "</body>",
      "</html>"
    ).mkString("", "\n", "\n")
  }

  private val brandMark: String =
    """<svg class="brand-mark" viewBox="0 0 100 100" aria-hidden="true">""" +
      """<path d="M22 90 V60 a14 14 0 0 1 28 0 V90 M50 60 a14 14 0 0 1 28 0 V90" fill="none" stroke="currentColor" stroke-width="8" stroke-linejoin="round" stroke-linecap="round"/>""" +
      """<path class="wave" d="M29 24 C36 13 45 13 50 22 C55 31 64 31 71 20" fill="none" stroke-width="7" stroke-linecap="round"/></svg>"""

  private def metricsCards(diff: DiffModel): String = {
    val (base, head) = (diff.metrics.base, diff.metrics.head)
    val cards        = ArchMetrics.rows.map { row =>
      val value = ArchMetrics.trend(row, base, head) match {
        case Some(worse) if diff.isDiff =>
          val (arrow, kind) = if (worse) ("▲ worse", "worse") else ("▼ better", "better")
          s"""${escape(row.format(row.value(base)))} → ${escape(row.format(row.value(head)))} <span class="trend $kind">$arrow</span>"""
        case _ => escape(row.format(row.value(head)))
      }
      s"""<div class="metric"><div class="metric-name">${escape(row.name)}</div><div class="metric-value">$value</div><div class="muted">${escape(
          row.description
        )}</div></div>"""
    }
    s"""<div class="metrics">${cards.mkString}</div>"""
  }

  private def summary(diff: DiffModel): String = {
    val warnings = if (diff.isDiff) diff.warnings.count(_.status == WarningStatus.New) else diff.warnings.size
    val label    = if (diff.isDiff) "new warnings" else "warnings"
    s"${GraphView.shown(diff).size} modules · ${diff.tables.size} tables · ${diff.topics.size + diff.events.size} events · $warnings $label"
  }

  private def statusClass(status: Status): String = status.toString.toLowerCase

  private def marker(status: Status): String = status match {
    case Status.Added     => "+ "
    case Status.Removed   => "− "
    case Status.Changed   => "~ "
    case Status.Unchanged => ""
  }

  private def moduleList(diff: DiffModel): String = {
    val chips = GraphView.shown(diff).map { m =>
      val state = if (m.status == Status.Unchanged && m.changedFiles.nonEmpty) "touched" else statusClass(m.status)
      s"""<button class="module-chip $state" data-module="${escape(m.id)}" data-status="$state">${escape(marker(m.status) + m.id)}</button>"""
    }
    s"""<nav class="module-list" aria-label="Modules">${chips.mkString}</nav>"""
  }

  private val Levels = List("modules" -> Level.Modules, "components" -> Level.Components, "everything" -> Level.Everything)

  private def diagrams(diff: DiffModel): String = {
    val levels = Levels.map((name, level) => name -> ujson.Str(Mermaid.diagram(GraphView.full(diff, level))))
    val focus  = "focus" -> ujson.Obj.from(GraphView.shown(diff).map { m =>
      m.id -> ujson.Obj.from(Levels.map((name, level) => name -> ujson.Str(Mermaid.diagram(GraphView.focus(diff, m.id, level)))))
    })
    val changed = Option.when(diff.isDiff) {
      "changed" -> ujson.Obj.from(Levels.map { (name, level) =>
        val graph = GraphView.changes(diff, level, collapse = false)
        name -> ujson.Str(if (graph.nodes.isEmpty) "" else Mermaid.diagram(graph))
      })
    }
    val ids = GraphView.shown(diff).flatMap { m =>
      (Mermaid.nodeId(s"module:${m.id}") -> m.id) :: m.components.map(c => Mermaid.nodeId(s"component:${m.id}:${c.name}") -> m.id)
    }
    ujson
      .write(ujson.Obj.from(levels ++ changed.toList :+ focus :+ ("ids" -> ujson.Obj.from(ids.map((k, v) => k -> ujson.Str(v))))))
      .replace("<", "\\u003c")
  }

  private def siteLabel(site: Site): String = s"${site.owner.label} / ${site.enclosing}"

  private def row(
    status: Status,
    cells: List[String],
    codeChanged: Boolean = false
  ): String = {
    val state = if (codeChanged) "touched" else statusClass(status)
    s"""<tr data-status="$state" class="$state">${cells.map(cell => s"<td>$cell</td>").mkString}</tr>"""
  }

  private def locCell(loc: Loc): String = s"""<code>${escape(s"${loc.path}:${loc.line}")}</code>"""

  private def eventsTable(diff: DiffModel): String = {
    val topics = diff.topics.flatMap(node =>
      node.head.orElse(node.base).map { topic =>
        row(
          node.status,
          List(
            escape(marker(node.status) + topic.name),
            "bus",
            escape(topic.eventType),
            escape(topic.owner.label),
            escape(topic.publishers.map(siteLabel).mkString(", ")),
            escape(topic.subscribers.map(siteLabel).mkString(", ")),
            locCell(topic.loc)
          ),
          node.codeChanged
        )
      }
    )
    val events = diff.events.flatMap(node =>
      node.head.orElse(node.base).map { event =>
        row(
          node.status,
          List(
            escape(marker(node.status) + event.eventType),
            "outbox",
            escape(event.typeName),
            escape(event.owner.label),
            escape(event.publishers.map(siteLabel).mkString(", ")),
            escape(event.subscribers.map(s => s"${s.consumer} (${siteLabel(s.site)})").mkString(", ")),
            locCell(event.loc)
          ),
          node.codeChanged
        )
      }
    )
    if (topics.isEmpty && events.isEmpty) """<p class="muted">No event-bus topics or outbox events.</p>"""
    else
      """<div class="scroll"><table><thead><tr><th>Name</th><th>Kind</th><th>Type</th><th>Owner</th><th>Publishers</th><th>Subscribers</th><th>Declared at</th></tr></thead><tbody>""" +
        (topics ++ events).mkString + "</tbody></table></div>"
  }

  private def tasksTable(diff: DiffModel): String = {
    val rows = diff.tasks.flatMap(node =>
      node.head.orElse(node.base).map { task =>
        row(
          node.status,
          List(
            escape(marker(node.status) + task.name),
            task.kind.label,
            escape(task.schedule.getOrElse("")),
            escape(task.owner.label),
            escape(task.scheduledFrom.map(siteLabel).distinct.mkString(", ")),
            locCell(task.loc)
          )
        )
      }
    )
    if (rows.isEmpty) """<p class="muted">No scheduled tasks.</p>"""
    else
      """<div class="scroll"><table><thead><tr><th>Name</th><th>Kind</th><th>Schedule</th><th>Owner</th><th>Scheduled from</th><th>Declared at</th></tr></thead><tbody>""" +
        rows.mkString + "</tbody></table></div>"
  }

  private def tablesMatrix(diff: DiffModel): String = {
    val tables  = diff.tables.flatMap(node => node.head.orElse(node.base).map(table => (node.status, node.codeChanged, table)))
    val modules = diff.modules.map(_.id)
    if (tables.isEmpty) """<p class="muted">No Table declarations.</p>"""
    else {
      val header = modules.map(id => s"<th>${escape(id)}</th>").mkString
      val rows   = tables.map {
        (
          status,
          codeChanged,
          table
        ) =>
          val cells = modules.map { id =>
            if (table.owner == Owner.Module(id)) """<span title="owner">●</span>"""
            else if (table.users.exists(_.owner == Owner.Module(id))) """<span title="user">○</span>"""
            else ""
          }
          row(status, escape(marker(status) + table.name) :: escape(table.owner.label) :: cells, codeChanged)
      }
      s"""<p class="muted">● owner · ○ uses it</p><div class="scroll"><table class="matrix"><thead><tr><th>Table</th><th>Owner</th>$header</tr></thead><tbody>${rows.mkString}</tbody></table></div>"""
    }
  }

  private def migrationsTable(diff: DiffModel): String = {
    val rows = diff.migrations.flatMap(node =>
      node.head.orElse(node.base).map { migration =>
        row(node.status, List(s"<code>${escape(marker(node.status) + migration.path)}</code>", escape(migration.summary(identity))))
      }
    )
    if (rows.isEmpty) """<p class="muted">No migrations.</p>"""
    else s"""<div class="scroll"><table><thead><tr><th>Migration</th><th>Tables</th></tr></thead><tbody>${rows.mkString}</tbody></table></div>"""
  }

  private def outsideTable(diff: DiffModel): String =
    if (diff.changedOutside.isEmpty) """<p class="muted">No code changed outside the modules.</p>"""
    else {
      val rows = diff.changedOutside.map(path =>
        row(Status.Unchanged, List(if (path.startsWith("main/")) "main" else "platform", s"<code>${escape(path)}</code>"), codeChanged = true)
      )
      """<p class="muted">Files in <code>main/</code> and in platform packages without a module, whose code changed.</p>""" +
        s"""<table><thead><tr><th>Where</th><th>File</th></tr></thead><tbody>${rows.mkString}</tbody></table>"""
    }

  private def badge(status: WarningStatus, isDiff: Boolean): String =
    if (isDiff) s"""<span class="badge ${status.toString.toLowerCase}">${status.toString}</span>""" else ""

  private def warningRow(status: WarningStatus, cells: List[String]): String = {
    val state = if (status == WarningStatus.Existing) "unchanged" else status.toString.toLowerCase
    s"""<tr data-status="$state" class="$state">${cells.map(cell => s"<td>$cell</td>").mkString}</tr>"""
  }

  private def warningsList(diff: DiffModel): String =
    if (diff.warnings.isEmpty) """<p class="muted">No warnings.</p>"""
    else
      "<table><thead><tr><th>Kind</th><th>Message</th><th>Where</th><th></th></tr></thead><tbody>" +
        diff.warnings.map { w =>
          warningRow(w.status, List(escape(w.warning.kind.toString), escape(w.warning.message), locCell(w.warning.loc), badge(w.status, diff.isDiff)))
        }.mkString + "</tbody></table>"

  private def unclassifiedList(diff: DiffModel): String =
    if (diff.unclassified.isEmpty) """<p class="muted">Nothing unclassified.</p>"""
    else
      "<table><thead><tr><th>Key</th><th>What</th><th>Where</th><th></th></tr></thead><tbody>" +
        diff.unclassified.map { u =>
          warningRow(u.status, List(escape(u.item.key), escape(u.item.description), locCell(u.item.loc), badge(u.status, diff.isDiff)))
        }.mkString + "</tbody></table>"

  private val styles: String =
    """
:root { --bg: #f5f1ea; --panel: #fffdf9; --fg: #1e1e24; --muted: #5c5b63; --border: #e2dcd2; --border-strong: #cfc9be; --node: #fffdf9; --accent: #b5122b; --added: #2f7d4a; --removed: #b5122b; --changed: #a8660b; --display: "Manrope", ui-sans-serif, system-ui, sans-serif; }
@media (prefers-color-scheme: dark) { :root { --bg: #1e1e24; --panel: #27272e; --fg: #f5f1ea; --muted: #b3ada3; --border: #35353d; --border-strong: #45454d; --node: #27272e; --accent: #e0304a; --added: #4caf73; --removed: #e0304a; --changed: #e0a43a; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg); font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
header { padding: 20px 24px 8px; border-bottom: 1px solid var(--border); }
h1, h2, h3 { font-family: var(--display); }
h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: -0.01em; }
h2 { font-size: 16px; margin: 24px 0 8px; }
.brand { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.brand-mark { width: 26px; height: 26px; color: var(--fg); }
.brand-mark .wave { stroke: var(--accent); }
.eyebrow { color: var(--accent); font-family: var(--display); font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; font-size: 12px; }
footer { padding: 16px 24px 32px; color: var(--muted); font-size: 12px; border-top: 1px solid var(--border); }
h3 { font-size: 13px; margin: 16px 0 4px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
main { padding: 0 24px 40px; }
.summary, .muted { color: var(--muted); }
.toggle { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; }
.modules-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: 16px; align-items: start; }
@media (max-width: 900px) { .modules-layout { grid-template-columns: 1fr; } }
.graph, .scroll { overflow: auto; border: 1px solid var(--border); border-radius: 8px; background: var(--panel); }
#details { border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; background: var(--panel); max-height: 80vh; overflow: auto; }
#details ul { margin: 0; padding-left: 18px; }
#details li { margin: 2px 0; }
#details code { overflow-wrap: anywhere; }
.graph { background: #f5f1ea; color: #1e1e24; min-height: 160px; padding: 12px; }
.graph svg { max-width: none; height: auto; }
.graph g.node, .graph g.cluster { cursor: pointer; }
.graph-controls { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 8px; }
.zoom { margin-left: auto; display: inline-flex; gap: 4px; }
.zoom button, #show-all { font: inherit; font-size: 13px; min-width: 32px; padding: 2px 8px; border-radius: 6px; border: 1px solid var(--border-strong); background: var(--panel); color: var(--fg); cursor: pointer; }
.module-list { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.module-chip { font: inherit; font-size: 13px; padding: 3px 10px; border-radius: 14px; border: 1px solid var(--border-strong); background: var(--panel); color: var(--fg); cursor: pointer; }
.module-chip:hover, .module-chip.selected { border-color: var(--fg); border-width: 2px; padding: 2px 9px; }
.module-chip.touched { border-color: var(--changed); border-style: dotted; } .module-chip.added { border-color: var(--added); } .module-chip.removed { border-color: var(--removed); border-style: dashed; } .module-chip.changed { border-color: var(--changed); }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--muted); font-weight: 600; }
.matrix td, .matrix th { text-align: center; }
.matrix td:first-child, .matrix td:nth-child(2), .matrix th:first-child, .matrix th:nth-child(2) { text-align: left; }
tr.added td:first-child { color: var(--added); }
tr.removed td:first-child { color: var(--removed); text-decoration: line-through; }
tr.changed td:first-child { color: var(--changed); } tr.touched td:first-child { color: var(--changed); text-decoration: underline dotted; }
tr.new td:first-child { color: var(--removed); }
tr.resolved td:first-child { color: var(--added); text-decoration: line-through; }
code { font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.badge { font-size: 11px; padding: 1px 6px; border-radius: 10px; border: 1px solid var(--border); }
.badge.new { color: var(--removed); border-color: var(--removed); }
.badge.resolved { color: var(--added); border-color: var(--added); }
.status { font-size: 11px; margin-left: 6px; }
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.metric { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; background: var(--panel); }
.metric-name { font-family: var(--display); font-weight: 600; }
.metric-value { font-size: 22px; font-weight: 600; margin: 4px 0; }
.trend { font-size: 13px; margin-left: 6px; }
.trend.worse { color: var(--removed); } .trend.better { color: var(--added); }
.status.added { color: var(--added); } .status.removed { color: var(--removed); } .status.changed { color: var(--changed); }
body.changed-only [data-status="unchanged"] { display: none; }
"""

  private val script: String =
    """
(() => {
  const data = JSON.parse(document.getElementById('arch-data').textContent);
  const panel = document.getElementById('details');
  const placeholder = [...panel.childNodes].map(child => child.cloneNode(true));
  const node = (tag, text, cls) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (cls) e.className = cls; return e; };
  const where = loc => loc && loc.path ? `${loc.path}:${loc.line}` : '';
  const moduleOf = owner => owner && owner['$type'] === 'Module' ? owner.id : null;
  const latest = diff => diff.head || diff.base;
  const section = (title, items) => {
    panel.appendChild(node('h3', title));
    if (items.length === 0) { panel.appendChild(node('p', 'none', 'muted')); return; }
    const list = node('ul');
    items.forEach(([text, loc, status]) => {
      const item = node('li');
      item.appendChild(node('span', text));
      if (status && status !== 'Unchanged') item.appendChild(node('span', status.toLowerCase(), `status ${status.toLowerCase()}`));
      if (loc) { item.appendChild(document.createElement('br')); item.appendChild(node('code', where(loc))); }
      list.appendChild(item);
    });
    panel.appendChild(list);
  };
  const show = id => {
    const diff = data.modules.find(m => m.id === id);
    if (!diff) return;
    const mod = latest(diff);
    panel.replaceChildren();
    panel.appendChild(node('h2', id));
    const changedFiles = diff.changedFiles || [];
    const state = diff.status !== 'Unchanged' ? diff.status.toLowerCase() : changedFiles.length ? 'code changed' : '';
    panel.appendChild(node('p', `${mod.traitName}${state ? ' · ' + state : ''}`, 'muted'));
    const m = mod.metrics;
    panel.appendChild(node('p', `${m.linesOfCode} lines · ${m.components} components · ${m.tablesOwned} tables · ${m.eventsOwned} events`, 'muted'));
    const coupling = data.metrics.coupling.find(c => c.id === id);
    if (coupling) panel.appendChild(node('p', `used by ${coupling.dependents} modules · depends on ${coupling.dependencies} · instability ${coupling.instability.toFixed(2)}`, 'muted'));
    const providerOf = member => {
      const wiring = data.wirings.map(latest).find(w => w.consumer === id && w.member === member);
      if (!wiring) return 'nothing';
      return wiring.provider['$type'] === 'FromModule' ? wiring.provider.id : 'platform';
    };
    const membersOf = name => diff.members.filter(m => m.section === name);
    if (changedFiles.length) section('Code changed', changedFiles.map(path => [path, null, null]));
    section('Consumes', membersOf('Consumes').map(m => [`${m.text} ← ${providerOf(m.name)}`, m.loc, m.status]));
    section('Provides', membersOf('Provides').map(m => [m.text, m.loc, m.status]));
    section('Contributes', membersOf('Contributes').map(m => [m.text, m.loc, m.status]));
    section('Components', diff.components.map(c => { const comp = latest(c); return [`${c.name} (${comp.kind.toLowerCase()})${comp.dependencies.length ? ' ← ' + comp.dependencies.join(', ') : ''}`, comp.loc, c.codeChanged ? 'code changed' : c.status]; }));
    section('Tables', data.tables.filter(t => moduleOf(latest(t).owner) === id).map(t => [latest(t).name, latest(t).loc, t.status]));
    section('Topics and events', data.topics.filter(t => moduleOf(latest(t).owner) === id).map(t => [`${latest(t).name} (bus)`, latest(t).loc, t.status])
      .concat(data.events.filter(e => moduleOf(latest(e).owner) === id).map(e => [`${latest(e).eventType} (outbox)`, latest(e).loc, e.status])));
    section('References out', data.references.filter(r => latest(r).from === id).map(r => { const ref = latest(r); return [`${ref.to}.${ref.layer}.${ref.symbol}${ref.explainedBy ? ' (' + ref.explainedBy.toLowerCase() + ')' : ''}`, ref.loc, r.status]; }));
    section('References in', data.references.filter(r => latest(r).to === id).map(r => { const ref = latest(r); return [`${ref.from} → ${ref.layer}.${ref.symbol}`, ref.loc, r.status]; }));
    document.querySelectorAll('.module-chip').forEach(chip => chip.classList.toggle('selected', chip.dataset.module === id));
  };
  const diagrams = JSON.parse(document.getElementById('arch-diagrams').textContent);
  const graph = document.getElementById('graph');
  const toggle = document.getElementById('changed-only');
  const showAll = document.getElementById('show-all');
  let focused = null;
  const levelName = () => { const level = document.querySelector('input[name=level]:checked'); return level ? level.value : 'modules'; };
  const source = () => {
    if (focused && diagrams.focus[focused]) return diagrams.focus[focused][levelName()];
    if (toggle && toggle.checked && 'changed' in diagrams) return diagrams.changed[levelName()];
    return diagrams[levelName()];
  };
  let natural = 0;
  let scale = 1;
  let drawing = 0;
  const fitScale = () => Math.min(1, (graph.clientWidth - 24) / natural);
  const zoom = next => {
    const svg = graph.querySelector('svg');
    if (!svg || !natural) return;
    scale = Math.min(3, Math.max(0.1, next));
    svg.style.width = `${Math.round(natural * scale)}px`;
  };
  const draw = () => {
    const ticket = ++drawing;
    showAll.hidden = !focused;
    if (!window.mermaid) { graph.replaceChildren(node('p', 'The diagram loads Mermaid from jsDelivr and needs network access. The module list above still opens each module.', 'muted')); return; }
    const text = source();
    if (!text) { graph.replaceChildren(node('p', 'No architecture changes.', 'muted')); return; }
    window.mermaid.render(`arch-${Math.random().toString(36).slice(2)}`, text).then(({ svg }) => {
      if (ticket !== drawing) return;
      graph.innerHTML = svg;
      const drawn = graph.querySelector('svg');
      natural = drawn.viewBox.baseVal.width;
      drawn.removeAttribute('width');
      drawn.removeAttribute('height');
      zoom(fitScale());
      graph.querySelectorAll('g.node, g.cluster').forEach(el => {
        const raw = el.classList.contains('cluster') ? el.id : (el.id.match(/^flowchart-(.+)-[0-9]+$/) || [])[1];
        const id = diagrams.ids[raw];
        if (id) el.addEventListener('click', event => { event.stopPropagation(); focusOn(id, !raw.startsWith('component_')); });
      });
    }).catch(error => { if (ticket === drawing) graph.replaceChildren(node('p', `The diagram could not be drawn: ${error.message}`, 'muted')); });
  };
  const unfocus = () => {
    focused = null;
    panel.replaceChildren(...placeholder.map(child => child.cloneNode(true)));
    document.querySelectorAll('.module-chip').forEach(chip => chip.classList.remove('selected'));
    draw();
  };
  const focusOn = (id, toggles) => {
    if (toggles && focused === id) unfocus();
    else { show(id); focused = id; draw(); }
  };
  if (window.mermaid) window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', maxTextSize: 1000000, maxEdges: 5000, flowchart: { useMaxWidth: false } });
  document.querySelectorAll('.module-chip').forEach(chip => chip.addEventListener('click', () => focusOn(chip.dataset.module, true)));
  showAll.addEventListener('click', unfocus);
  document.querySelectorAll('input[name=level]').forEach(radio => radio.addEventListener('change', draw));
  document.querySelectorAll('[data-zoom]').forEach(button => button.addEventListener('click', () => {
    const step = { in: scale * 1.25, out: scale / 1.25 };
    zoom(step[button.dataset.zoom] ?? fitScale());
  }));
  if (toggle) toggle.addEventListener('change', () => { document.body.classList.toggle('changed-only', toggle.checked); unfocus(); });
  draw();
})();
"""
}
