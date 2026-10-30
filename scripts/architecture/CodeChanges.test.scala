package architecture

class CodeChangesSuite extends munit.FunSuite {
  private def project(validity: String, cart: String = "") = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AuthModule with CartModule"),
    Fixtures.src("auth/AuthModule.scala", "package app.auth\ntrait AuthModule"),
    Fixtures.src("auth/services/TokenPolicy.scala", s"package app.auth.services\nclass TokenPolicy {\n  def validFor = $validity\n}"),
    Fixtures.src("cart/CartModule.scala", s"package app.cart\ntrait CartModule {\n$cart\n}")
  )
  private val base = Extractor.extract(project("30.minutes"))

  private def against(head: ProjectInput) = ArchDiff.compare(base, Extractor.extract(head), "main", "working tree")

  test("a module whose code changed but whose architecture didn't lists the files that changed") {
    assertEquals(
      against(project("30.days")).modules.map(m => (m.id, m.status, m.changedFiles)),
      List(("auth", Status.Unchanged, List("auth/services/TokenPolicy.scala")), ("cart", Status.Unchanged, Nil))
    )
  }

  test("comments and formatting are not code changes") {
    assertEquals(against(project("// a longer validity\n    30.minutes")).modules.flatMap(_.changedFiles), Nil)
  }

  test("renaming the root package is not a code change") {
    val renamed =
      ProjectInput("shop", project("30.minutes").scalaFiles.map(f => f.copy(content = f.content.replace("package app.", "package shop."))), Nil)
    assertEquals(against(renamed).modules.flatMap(_.changedFiles), Nil)
  }

  test("the pull request output lists code changes, and says there are no architecture changes when that's all there is") {
    val text = Mermaid.render(against(project("30.days")))
    assert(text.startsWith("No architecture changes between `main` and `working tree`."), text)
    assert(text.contains("**Code changed, architecture unchanged**\n\n- auth: `services/TokenPolicy.scala`"), text)
  }

  test("a module with only code changes joins the changes diagram with a dotted outline, without its neighbours") {
    val diff  = against(project("30.days", "  lazy val cartService: CartService = wire[CartService]"))
    val graph = GraphView.changes(diff)
    assertEquals(graph.nodes.filter(_.kind == NodeKind.Module).map(n => (n.key, n.codeChanged)), List("module:auth" -> true, "module:cart" -> false))
    assert(Mermaid.diagram(graph).contains("class module_auth touched"), Mermaid.diagram(graph))
  }

  test("the page marks the module's pill and keeps it under Changed only") {
    assert(Html.render(against(project("30.days"))).contains("""<button class="module-chip touched" data-module="auth" data-status="touched">"""))
  }

  test("a utils module whose code changed is shown") {
    def withOutbox(retries: String) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends OutboxModule"),
      Fixtures.src("utils/outbox/OutboxModule.scala", s"package app.utils.outbox\ntrait OutboxModule {\n  def retries = $retries\n}")
    )
    val diff = ArchDiff.compare(Extractor.extract(withOutbox("3")), Extractor.extract(withOutbox("5")), "b", "h")
    assertEquals(GraphView.shown(diff).map(_.id), List("utils/outbox"))
  }
}
