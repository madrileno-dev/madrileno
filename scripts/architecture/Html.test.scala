package architecture

class HtmlSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")
  private val page = Html.render(diff)

  test("diff page has title, module nodes, changed-only filter and embedded data") {
    assert(page.contains("<title>Architecture diff: main@abc1234 → working tree</title>"))
    assert(page.contains("""<button class="module-chip added" data-module="order">+ order</button>"""))
    assert(page.contains("Changed only"))
    assert(page.contains("<script type=\"application/json\" id=\"arch-data\">"))
  }

  test("model text cannot inject markup") {
    val evil = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends XModule"),
      Fixtures.src(
        "x/XModule.scala",
        "package app.x\ntrait XModule extends MailPreviewProvider {\n  override abstract def mailPreviews = super.mailPreviews :+ html\"</script><b>\"\n}"
      )
    )
    val out = Html.render(ArchDiff.full(Extractor.extract(evil), "working tree"))
    assert(!out.contains("</script><b>"))
    assert(out.contains("\\u003c/script>\\u003cb>"))
  }

  test("full page has no changed-only filter") {
    assert(!Html.render(ArchDiff.full(Fixtures.diffHead, "working tree")).contains("Changed only"))
  }

  test("the page carries the Madrileño brand") {
    assert(page.contains("""<svg class="brand-mark""""), page.take(2000))
    assert(page.contains(">Madrileño<"))
    assert(page.contains("--accent: #b5122b"))
    assert(page.contains("Manrope"))
  }

  test("the page shows the metrics, with base and branch in a diff") {
    val cyclic = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
        Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule"),
        Fixtures.src("b/BModule.scala", "package app.b\nimport app.a.domain.Y\ntrait BModule")
      )
    )
    val flat = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
        Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
        Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule")
      )
    )
    val diffPage = Html.render(ArchDiff.compare(flat, cyclic, "b", "h"))
    assert(diffPage.contains("""<section id="metrics">"""))
    assert(diffPage.contains("Propagation cost"))
    assert(diffPage.contains("0% → 100%"))
    assert(Html.render(ArchDiff.full(cyclic, "h")).contains(">100%<"))
    assert(diffPage.contains("\"dependents\""))
  }

  test("the only external resource is the pinned Mermaid script with an integrity hash") {
    assertEquals("https?://[^\"'\\s<>)]+".r.findAllIn(page).toList.distinct, List(Html.MermaidUrl))
    assert(page.contains(s"""<script src="${Html.MermaidUrl}" integrity="${Html.MermaidIntegrity}" crossorigin="anonymous"></script>"""))
  }

  test("the diagram has zoom controls, so a dense graph fitted to the page can still be read") {
    assert(
      page.contains(
        """<span class="zoom"><button type="button" data-zoom="out" aria-label="Zoom out">−</button><button type="button" data-zoom="fit">Fit</button><button type="button" data-zoom="in" aria-label="Zoom in">+</button></span>"""
      ),
      page
    )
    assert(page.contains("useMaxWidth: false"), "the script sizes the diagram itself")
  }

  test("the click handler's pattern finds the node id inside the id Mermaid gives a node") {
    val pattern = """el\.id\.match\(/(.*?)/\)""".r.findFirstMatchIn(page).get.group(1)
    assertEquals(pattern.r.findFirstMatchIn("flowchart-module_order-12").map(_.group(1)), Some("module_order"))
  }

  test("the page embeds one Mermaid diagram per detail level, plus the changes in a diff") {
    val diagrams = """<script type="application/json" id="arch-diagrams">(.*?)</script>""".r.findFirstMatchIn(page).get.group(1)
    val parsed   = ujson.read(diagrams)
    assertEquals(parsed.obj.keySet.toSet, Set("modules", "components", "everything", "changed", "ids"))
    assert(parsed("modules").str.contains("flowchart LR"))
    assert(parsed("changed").str.contains("module_order"))
    assertEquals(parsed("ids")("module_order").str, "order")
    assert(
      !ujson
        .read(
          """<script type="application/json" id="arch-diagrams">(.*?)</script>""".r
            .findFirstMatchIn(Html.render(ArchDiff.full(Fixtures.diffHead, "h")))
            .get
            .group(1)
        )
        .obj
        .contains("changed")
    )
  }
}
