package architecture

object Html {
  private val NodeWidth  = 160.0
  private val NodeHeight = 44.0

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
      s"<h1>${escape(title)}</h1>",
      s"""<p class="summary">${escape(summary(diff))}</p>""",
      if (diff.isDiff) """<label class="toggle"><input type="checkbox" id="changed-only"> Changed only</label>""" else "",
      "</header>",
      "<main>",
      """<section id="modules"><h2>Modules</h2><div class="modules-layout">""",
      s"""<div class="graph">${svg(diff)}</div>""",
      """<aside id="details"><p class="muted">Click a module to see what it consumes, provides and contributes.</p></aside>""",
      "</div></section>",
      s"""<section id="events"><h2>Events</h2>${eventsTable(diff)}</section>""",
      s"""<section id="tables"><h2>Tables</h2>${tablesMatrix(diff)}</section>""",
      s"""<section id="warnings"><h2>Warnings</h2>${warningsList(diff)}</section>""",
      """<section id="unclassified"><h2>Unclassified</h2>""",
      """<p class="muted">The map reads source, not compiled code. Anything it saw but could not place is listed here instead of being guessed.</p>""",
      s"${unclassifiedList(diff)}</section>",
      "</main>",
      s"""<script type="application/json" id="arch-data">$data</script>""",
      s"<script>$script</script>",
      "</body>",
      "</html>"
    ).mkString("", "\n", "\n")
  }

  private def summary(diff: DiffModel): String = {
    val warnings = if (diff.isDiff) diff.warnings.count(_.status == WarningStatus.New) else diff.warnings.size
    val label    = if (diff.isDiff) "new warnings" else "warnings"
    s"${diff.modules.size} modules · ${diff.tables.size} tables · ${diff.topics.size + diff.events.size} events · $warnings $label"
  }

  private def statusClass(status: Status): String = status.toString.toLowerCase

  private def marker(status: Status): String = status match {
    case Status.Added     => "+ "
    case Status.Removed   => "− "
    case Status.Changed   => "~ "
    case Status.Unchanged => ""
  }

  private def svg(diff: DiffModel): String = {
    val graph  = GraphView.modules(diff)
    val points = Layout.layered(graph.nodes.map(_.key), graph.edges.map(e => e.from -> e.to))
    val width  = points.values.map(_.x).maxOption.getOrElse(0.0) + NodeWidth + 40
    val height = points.values.map(_.y).maxOption.getOrElse(0.0) + NodeHeight + 40
    val edges  = graph.edges.flatMap { edge =>
      (points.get(edge.from), points.get(edge.to)) match {
        case (Some(from), Some(to)) =>
          val (x1, y1) = (from.x + NodeWidth / 2, from.y + NodeHeight)
          val (x2, y2) = (to.x + NodeWidth / 2, to.y)
          val bend     = math.max(40.0, math.abs(y2 - y1) / 2)
          val classes  = s"edge ${statusClass(edge.status)}${if (edge.repositories) " edge-repositories" else ""}"
          List(
            s"""<g class="$classes" data-status="${statusClass(edge.status)}"><title>${escape(
                s"${edge.from} → ${edge.to}: ${edge.label}"
              )}</title>""" +
              s"""<path d="M $x1 $y1 C $x1 ${y1 + bend}, $x2 ${y2 - bend}, $x2 $y2" marker-end="url(#arrow)"/></g>"""
          )
        case _ => Nil
      }
    }
    val nodes = graph.nodes.flatMap { node =>
      points.get(node.key).map { point =>
        s"""<g class="node ${statusClass(node.status)}" data-key="${escape(node.key)}" data-status="${statusClass(node.status)}" tabindex="0">""" +
          s"""<rect x="${point.x}" y="${point.y}" width="$NodeWidth" height="$NodeHeight" rx="8"/>""" +
          s"""<text x="${point.x + NodeWidth / 2}" y="${point.y + NodeHeight / 2 + 5}">${escape(marker(node.status) + node.label)}</text></g>"""
      }
    }
    s"""<svg viewBox="0 0 $width $height" width="$width" height="$height" role="img" aria-label="Module dependency graph">""" +
      """<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z"/></marker></defs>""" +
      edges.mkString + nodes.mkString + "</svg>"
  }

  private def ownerLabel(owner: Owner): String = owner match {
    case Owner.Module(id)      => id
    case Owner.Platform(id)    => s"platform ($id)"
    case Owner.CompositionRoot => "main"
  }

  private def siteLabel(site: Site): String = s"${ownerLabel(site.owner)} / ${site.enclosing}"

  private def row(status: Status, cells: List[String]): String =
    s"""<tr data-status="${statusClass(status)}" class="${statusClass(status)}">${cells.map(cell => s"<td>$cell</td>").mkString}</tr>"""

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
            escape(ownerLabel(topic.owner)),
            escape(topic.publishers.map(siteLabel).mkString(", ")),
            escape(topic.subscribers.map(siteLabel).mkString(", ")),
            locCell(topic.loc)
          )
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
            escape(ownerLabel(event.owner)),
            escape(event.publishers.map(siteLabel).mkString(", ")),
            escape(event.subscribers.map(s => s"${s.consumer} (${siteLabel(s.site)})").mkString(", ")),
            locCell(event.loc)
          )
        )
      }
    )
    if (topics.isEmpty && events.isEmpty) """<p class="muted">No event-bus topics or outbox events.</p>"""
    else
      "<table><thead><tr><th>Name</th><th>Kind</th><th>Type</th><th>Owner</th><th>Publishers</th><th>Subscribers</th><th>Declared at</th></tr></thead><tbody>" +
        (topics ++ events).mkString + "</tbody></table>"
  }

  private def tablesMatrix(diff: DiffModel): String = {
    val tables  = diff.tables.flatMap(node => node.head.orElse(node.base).map(node.status -> _))
    val modules = diff.modules.map(_.id)
    if (tables.isEmpty) """<p class="muted">No Table declarations.</p>"""
    else {
      val header = modules.map(id => s"<th>${escape(id)}</th>").mkString
      val rows   = tables.map { (status, table) =>
        val cells = modules.map { id =>
          if (table.owner == Owner.Module(id)) """<span title="owner">●</span>"""
          else if (table.users.exists(_.owner == Owner.Module(id))) """<span title="user">○</span>"""
          else ""
        }
        row(status, escape(marker(status) + table.name) :: escape(ownerLabel(table.owner)) :: cells)
      }
      s"""<p class="muted">● owner · ○ uses it</p><div class="scroll"><table class="matrix"><thead><tr><th>Table</th><th>Owner</th>$header</tr></thead><tbody>${rows.mkString}</tbody></table></div>"""
    }
  }

  private def badge(status: WarningStatus, isDiff: Boolean): String =
    if (isDiff) s"""<span class="badge ${status.toString.toLowerCase}">${status.toString}</span>""" else ""

  private def warningStatusRow(status: WarningStatus): Status = status match {
    case WarningStatus.New      => Status.Added
    case WarningStatus.Resolved => Status.Removed
    case WarningStatus.Existing => Status.Unchanged
  }

  private def warningsList(diff: DiffModel): String =
    if (diff.warnings.isEmpty) """<p class="muted">No warnings.</p>"""
    else
      "<table><thead><tr><th>Kind</th><th>Message</th><th>Where</th><th></th></tr></thead><tbody>" +
        diff.warnings.map { w =>
          row(
            warningStatusRow(w.status),
            List(escape(w.warning.kind.toString), escape(w.warning.message), locCell(w.warning.loc), badge(w.status, diff.isDiff))
          )
        }.mkString + "</tbody></table>"

  private def unclassifiedList(diff: DiffModel): String =
    if (diff.unclassified.isEmpty) """<p class="muted">Nothing unclassified.</p>"""
    else
      "<table><thead><tr><th>Key</th><th>What</th><th>Where</th><th></th></tr></thead><tbody>" +
        diff.unclassified.map { u =>
          row(warningStatusRow(u.status), List(escape(u.item.key), escape(u.item.description), locCell(u.item.loc), badge(u.status, diff.isDiff)))
        }.mkString + "</tbody></table>"

  private val styles: String =
    """
:root { --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --border:#d1d9e0; --panel:#f6f8fa; --node:#ffffff; --added:#1a7f37; --removed:#cf222e; --changed:#9a6700; --accent:#0969da; }
@media (prefers-color-scheme: dark) { :root { --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --border:#3d444d; --panel:#151b23; --node:#151b23; --added:#3fb950; --removed:#f85149; --changed:#d29922; --accent:#4493f8; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg); font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
header { padding: 20px 24px 8px; border-bottom: 1px solid var(--border); }
h1 { font-size: 20px; margin: 0 0 4px; }
h2 { font-size: 16px; margin: 24px 0 8px; }
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
svg text { font-size: 13px; fill: var(--fg); text-anchor: middle; pointer-events: none; }
.node rect { fill: var(--node); stroke: var(--border); stroke-width: 1.5; }
.node { cursor: pointer; }
.node:hover rect, .node.selected rect, .node:focus rect { stroke: var(--accent); stroke-width: 2.5; }
.node.added rect { stroke: var(--added); stroke-width: 2.5; }
.node.removed rect { stroke: var(--removed); stroke-width: 2.5; stroke-dasharray: 5 3; }
.node.changed rect { stroke: var(--changed); stroke-width: 2.5; }
.edge path { fill: none; stroke: var(--muted); stroke-width: 1.4; }
.edge:hover path { stroke: var(--accent); stroke-width: 2.4; }
.edge.added path { stroke: var(--added); stroke-width: 2.2; }
.edge.removed path { stroke: var(--removed); stroke-dasharray: 5 3; }
.edge-repositories path { stroke-width: 2.6; stroke-dasharray: 7 3; }
marker path { fill: var(--muted); }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--muted); font-weight: 600; }
.matrix td, .matrix th { text-align: center; }
.matrix td:first-child, .matrix td:nth-child(2), .matrix th:first-child, .matrix th:nth-child(2) { text-align: left; }
tr.added td:first-child { color: var(--added); }
tr.removed td:first-child { color: var(--removed); text-decoration: line-through; }
tr.changed td:first-child { color: var(--changed); }
code { font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.badge { font-size: 11px; padding: 1px 6px; border-radius: 10px; border: 1px solid var(--border); }
.badge.new { color: var(--removed); border-color: var(--removed); }
.badge.resolved { color: var(--added); border-color: var(--added); }
.status { font-size: 11px; margin-left: 6px; }
.status.added { color: var(--added); } .status.removed { color: var(--removed); } .status.changed { color: var(--changed); }
body.changed-only [data-status="unchanged"] { display: none; }
"""

  private val script: String =
    """
(() => {
  const data = JSON.parse(document.getElementById('arch-data').textContent);
  const opt = value => Array.isArray(value) ? value[0] : (value === null ? undefined : value);
  const panel = document.getElementById('details');
  const node = (tag, text, cls) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (cls) e.className = cls; return e; };
  const where = loc => loc && loc.path ? `${loc.path}:${loc.line}` : '';
  const moduleOf = owner => owner && owner['$type'] === 'Module' ? owner.id : null;
  const latest = diff => opt(diff.head) || opt(diff.base);
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
    panel.appendChild(node('p', `${mod.traitName}${diff.status !== 'Unchanged' ? ' · ' + diff.status.toLowerCase() : ''}`, 'muted'));
    const m = mod.metrics;
    panel.appendChild(node('p', `${m.linesOfCode} lines · ${m.components} components · ${m.tablesOwned} tables · ${m.eventsOwned} events`, 'muted'));
    const providerOf = member => {
      const wiring = data.wirings.map(latest).find(w => w.consumer === id && w.member === member);
      if (!wiring) return 'nothing';
      return wiring.provider['$type'] === 'FromModule' ? wiring.provider.id : 'platform';
    };
    section('Consumes', mod.consumes.map(c => [`${c.name}: ${c.tpe} ← ${providerOf(c.name)}`, c.loc]));
    section('Provides', mod.provides.map(p => [`${p.name}: ${p.tpe}`, p.loc]));
    section('Contributes', mod.contributions.map(c => [`${c.registry}: ${c.items.join(', ')}`, c.loc]));
    section('Components', diff.components.map(c => { const comp = latest(c); return [`${c.name} (${comp.kind.toLowerCase()})${comp.dependencies.length ? ' ← ' + comp.dependencies.join(', ') : ''}`, comp.loc, c.status]; }));
    section('Tables', data.tables.filter(t => moduleOf(latest(t).owner) === id).map(t => [latest(t).name, latest(t).loc, t.status]));
    section('Topics and events', data.topics.filter(t => moduleOf(latest(t).owner) === id).map(t => [`${latest(t).name} (bus)`, latest(t).loc, t.status])
      .concat(data.events.filter(e => moduleOf(latest(e).owner) === id).map(e => [`${latest(e).eventType} (outbox)`, latest(e).loc, e.status])));
    section('References out', data.references.filter(r => latest(r).from === id).map(r => { const ref = latest(r); return [`${ref.to}.${ref.layer}.${ref.symbol}`, ref.loc, r.status]; }));
    section('References in', data.references.filter(r => latest(r).to === id).map(r => { const ref = latest(r); return [`${ref.from} → ${ref.layer}.${ref.symbol}`, ref.loc, r.status]; }));
    document.querySelectorAll('.node.selected').forEach(n => n.classList.remove('selected'));
    const selected = document.querySelector(`.node[data-key="module:${CSS.escape(id)}"]`);
    if (selected) selected.classList.add('selected');
  };
  document.querySelectorAll('.node[data-key^="module:"]').forEach(n => {
    const id = n.getAttribute('data-key').slice('module:'.length);
    n.addEventListener('click', () => show(id));
    n.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); show(id); } });
  });
  const toggle = document.getElementById('changed-only');
  if (toggle) toggle.addEventListener('change', () => document.body.classList.toggle('changed-only', toggle.checked));
})();
"""
}
