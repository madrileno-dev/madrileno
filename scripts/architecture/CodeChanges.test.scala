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

  private def wired(validity: String, extra: String = "") = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AuthModule"),
    Fixtures
      .src("auth/AuthModule.scala", s"package app.auth\ntrait AuthModule {\n  lazy val tokenPolicy: TokenPolicy = wire[TokenPolicy]\n$extra\n}"),
    Fixtures.src("auth/services/TokenPolicy.scala", s"package app.auth.services\nclass TokenPolicy {\n  def validFor = $validity\n}"),
    Fixtures.src("auth/services/Sessions.scala", "package app.auth.services\nclass Sessions")
  )

  test("a component whose file changed but whose dependencies didn't gets the dotted outline, inside an amber module too") {
    val base = Extractor.extract(wired("30.minutes"))
    val head = Extractor.extract(wired("30.days", "  lazy val sessions: Sessions = wire[Sessions]"))
    val diff = ArchDiff.compare(base, head, "b", "h")
    assertEquals(diff.modules.map(_.status), List(Status.Changed))
    assertEquals(
      diff.modules.flatMap(_.components).map(c => (c.name, c.status, c.codeChanged)),
      List(("Sessions", Status.Added, false), ("TokenPolicy", Status.Unchanged, true))
    )
    val graph = GraphView.changes(diff)
    assertEquals(
      graph.nodes.filter(_.kind == NodeKind.Component).map(n => n.key -> n.codeChanged),
      List("component:auth:Sessions" -> false, "component:auth:TokenPolicy" -> true)
    )
    assert(Mermaid.diagram(graph).contains("class component_auth_TokenPolicy touched"), Mermaid.diagram(graph))
  }

  private def withEvents(userDeleted: String, shopEvent: String) = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
    Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
    Fixtures.src(
      "user/domain/UserDeleted.scala",
      s"package app.user.domain\nfinal case class UserDeleted($userDeleted)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
    ),
    Fixtures.src(
      "user/services/UserService.scala",
      "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
    ),
    Fixtures.src(
      "shop/ShopModule.scala",
      "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[ShopEvent] = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n}"
    ),
    Fixtures.src("shop/domain/ShopEvent.scala", s"package app.shop.domain\nfinal case class ShopEvent($shopEvent)")
  )

  test("an event or topic whose payload type changed gets the dotted outline and is listed") {
    val diff = ArchDiff.compare(
      Extractor.extract(withEvents("id: Id", "orderId: Id")),
      Extractor.extract(withEvents("id: Id, reason: String", "orderId: Id, total: Money")),
      "main",
      "working tree"
    )
    assertEquals(diff.events.map(e => (e.key, e.status, e.codeChanged)), List(("user-deleted.v1", Status.Unchanged, true)))
    assertEquals(diff.topics.map(t => (t.key, t.status, t.codeChanged)), List(("shop_events", Status.Unchanged, true)))
    assert(GraphView.changes(diff).nodes.exists(n => n.key == "event:user-deleted.v1" && n.codeChanged))
    val text = Mermaid.render(diff)
    assert(text.contains("- event `user-deleted.v1`: `user/domain/UserDeleted.scala`"), text)
    assert(text.contains("- topic `shop_events`: `shop/domain/ShopEvent.scala`"), text)
    assert(Html.render(diff).contains("""<tr data-status="touched" class="touched"><td>user-deleted.v1</td>"""))
  }
}
