package architecture

class HtmlSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")
  private val page = Html.render(diff)

  test("layout is deterministic, layered by dependency, and never overlaps") {
    val points = Layout.layered(List("a", "b", "c"), List("a" -> "b", "b" -> "c", "c" -> "a"))
    assertEquals(points, Layout.layered(List("a", "b", "c"), List("a" -> "b", "b" -> "c", "c" -> "a")))
    assert(points("a").y < points("b").y && points("b").y < points("c").y)
    assertEquals(points.values.toSet.size, 3)
  }

  test("self-contained: no external scripts, styles or URLs") {
    assert(!page.contains("<script src"))
    assert(!page.contains("<link"))
    assert("https?://".r.findFirstIn(page).isEmpty)
  }

  test("diff page has title, module nodes, changed-only filter and embedded data") {
    assert(page.contains("<title>Architecture diff: main@abc1234 → working tree</title>"))
    assert(page.contains("data-key=\"module:order\""))
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

  private val cyclic = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
      Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\nimport app.a.domain.Y\ntrait BModule")
    )
  )

  test("edges of a cyclic graph stay inside the canvas") {
    val out     = Html.render(ArchDiff.full(cyclic, "working tree"))
    val viewBox = """viewBox="0 0 ([0-9.]+) ([0-9.]+)"""".r.findFirstMatchIn(out).get
    val (w, h)  = (viewBox.group(1).toDouble, viewBox.group(2).toDouble)
    val numbers =
      """<path d="M ([^"]+)" marker-end""".r.findAllMatchIn(out).flatMap(_.group(1).split("[ ,MC]+").filter(_.nonEmpty).map(_.toDouble)).toList
    assert(numbers.nonEmpty)
    assert(numbers.forall(n => n >= 0 && n <= math.max(w, h)), numbers.mkString(" "))
  }

  test("changed-only keeps unchanged nodes that a changed edge points at") {
    assert("""data-key="module:user" data-status="touched"""".r.findFirstIn(page).isDefined)
  }
}
