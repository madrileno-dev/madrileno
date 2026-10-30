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
    assert(text.contains("**Code changed**\n\n- auth: `services/TokenPolicy.scala`"), text)
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

  private def twoCreated(a: String, b: String) = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
    Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
    Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
    Fixtures.src(
      "a/domain/Created.scala",
      s"package app.a.domain\nfinal case class Created($a)\nobject Created {\n  given DomainEventDescriptor[Created] = DomainEventDescriptor(\"a-created.v1\", \"a\", _.id)\n}"
    ),
    Fixtures.src("b/domain/Created.scala", s"package app.b.domain\nfinal case class Created($b)")
  )

  test("an event's payload is the type in its own module, not another module's type with the same name") {
    val base     = Extractor.extract(twoCreated("id: Id", "name: String"))
    val bChanged = ArchDiff.compare(base, Extractor.extract(twoCreated("id: Id", "name: String, at: Instant")), "b", "h")
    val aChanged = ArchDiff.compare(base, Extractor.extract(twoCreated("id: Id, at: Instant", "name: String")), "b", "h")
    assertEquals(bChanged.events.map(_.codeChanged), List(false))
    assertEquals(aChanged.events.map(_.codeChanged), List(true))
    assert(Mermaid.render(aChanged).contains("- event `a-created.v1`: `a/domain/Created.scala`"), Mermaid.render(aChanged))
  }

  test("taking a module out of ApplicationLoader is an architecture change") {
    def loader(modules: String) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", s"package app.main\nclass ApplicationLoader extends $modules"),
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule")
    )
    val diff = ArchDiff.compare(Extractor.extract(loader("AModule with BModule")), Extractor.extract(loader("AModule")), "b", "h")
    assertEquals(diff.modules.map(m => m.id -> m.status), List("a" -> Status.Unchanged, "b" -> Status.Changed))
  }

  private def withAccounts(columns: String, migrations: List[SourceFile] = Nil) = Fixtures.projectWithMigrations(
    List(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/repositories/AccountTable.scala",
        s"package app.user.repositories\nobject AccountTable extends Table[Account](\"account\") {\n$columns\n}"
      )
    ),
    migrations
  )
  private val idOnly = "  val id = column(\"id\", uuid)"

  test("a table whose columns changed gets the dotted outline and is listed") {
    val diff = ArchDiff.compare(
      Extractor.extract(withAccounts(idOnly)),
      Extractor.extract(
        withAccounts("  val id = column(\"id\", uuid.as[AccountId])\n  val email = column(\"email\", text)\n  val name = column(\"name\", text)")
      ),
      "main",
      "working tree"
    )
    assertEquals(diff.tables.map(t => (t.key, t.status, t.codeChanged)), List(("account", Status.Unchanged, true)))
    assert(GraphView.changes(diff).nodes.exists(n => n.key == "table:account" && n.codeChanged))
    val text = Mermaid.render(diff)
    assert(text.contains("- table `account`: added columns `email`, `name`; changed column `id`"), text)
    assert(Html.render(diff).contains("""<tr data-status="touched" class="touched"><td>account</td>"""))
  }

  test("added and changed migrations are listed, and the tables they touch get the dotted outline") {
    val v1     = Fixtures.migration("V1__init.sql", "create table account (id uuid primary key);")
    val edited = Fixtures.migration("V1__init.sql", "create table account (id uuid primary key, created_at timestamptz);")
    val v2     = Fixtures.migration("V2__email.sql", "alter table account add column email text;")
    val diff   = ArchDiff.compare(
      Extractor.extract(withAccounts(idOnly, List(v1))),
      Extractor.extract(withAccounts(idOnly, List(edited, v2))),
      "main",
      "working tree"
    )
    assertEquals(diff.migrations.map(m => m.key -> m.status), List("V1__init.sql" -> Status.Changed, "V2__email.sql" -> Status.Added))
    assertEquals(diff.tables.map(t => t.key -> t.codeChanged), List("account" -> true))
    val text = Mermaid.render(diff)
    assert(text.startsWith("No architecture changes"), text)
    assert(text.contains("- table `account`: migrations `V1__init.sql`, `V2__email.sql`"), text)
    assert(text.contains("**Migrations**\n\n- changed `V1__init.sql`: creates `account`\n- added `V2__email.sql`: alters `account`"), text)
    val page = Html.render(diff)
    assert(page.contains("""<section id="migrations"><h2>Migrations</h2>"""), page)
    assert(page.contains("""<tr data-status="added" class="added"><td><code>+ V2__email.sql</code></td><td>alters account</td></tr>"""), page)
  }

  test("a migration whose comments changed is not a change") {
    val v1   = Fixtures.migration("V1__init.sql", "create table account (id uuid primary key);")
    val diff = ArchDiff.compare(
      Extractor.extract(withAccounts(idOnly, List(v1))),
      Extractor.extract(
        withAccounts(idOnly, List(Fixtures.migration("V1__init.sql", "-- accounts\ncreate table account (\n  id uuid primary key\n);")))
      ),
      "b",
      "h"
    )
    assertEquals(diff.migrations.map(_.status), List(Status.Unchanged))
    assertEquals(diff.tables.map(_.codeChanged), List(false))
  }

  test("code changed outside the modules is listed as platform or main") {
    def app(retries: String, loader: String) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", s"package app.main\nclass ApplicationLoader extends AuthModule {\n$loader\n}"),
      Fixtures.src("auth/AuthModule.scala", "package app.auth\ntrait AuthModule"),
      Fixtures.src("utils/task/Scheduler.scala", s"package app.utils.task\nclass Scheduler {\n  def retries = $retries\n}"),
      Fixtures.src("utils/db/Db.scala", "package app.utils.db\nclass Db")
    )
    val diff = ArchDiff.compare(Extractor.extract(app("3", "")), Extractor.extract(app("5", "  def port = 9000")), "main", "working tree")
    assertEquals(diff.changedOutside, List("main/ApplicationLoader.scala", "utils/task/Scheduler.scala"))
    val text = Mermaid.render(diff)
    assert(text.startsWith("No architecture changes"), text)
    assert(text.contains("**Code changed**\n\n- platform: `utils/task/Scheduler.scala`\n- main: `ApplicationLoader.scala`"), text)
    val page = Html.render(diff)
    assert(page.contains("""<section id="outside"><h2>Code outside modules</h2>"""), page)
    assert(
      page.contains("""<tr data-status="touched" class="touched"><td>platform</td><td><code>utils/task/Scheduler.scala</code></td></tr>"""),
      page
    )
    assert(!Html.render(ArchDiff.full(Extractor.extract(app("3", "")), "h")).contains("""<section id="outside">"""))
  }

  test("an event whose payload type lives outside the modules is marked when that file changes") {
    def app(payload: String) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nobject Deleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src("utils/events/UserDeleted.scala", s"package app.utils.events\nfinal case class UserDeleted($payload)")
    )
    val diff = ArchDiff.compare(Extractor.extract(app("id: Id")), Extractor.extract(app("id: Id, at: Instant")), "b", "h")
    assertEquals(diff.events.map(_.codeChanged), List(true))
  }

  test("a table that changed for another reason still lists its column changes") {
    def users(columns: String, use: String) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
      Fixtures.src(
        "user/repositories/AccountTable.scala",
        s"package app.user.repositories\nobject AccountTable extends Table[Account](\"account\") {\n$columns\n}"
      ),
      Fixtures.src("shop/repositories/ShopRepository.scala", s"package app.shop.repositories\nclass ShopRepository {\n$use\n}")
    )
    val diff = ArchDiff.compare(
      Extractor.extract(users(idOnly, "")),
      Extractor.extract(users(idOnly + "\n  val email = column(\"email\", text)", "  def f = AccountTable")),
      "b",
      "h"
    )
    assertEquals(diff.tables.map(t => (t.status, t.codeChanged)), List((Status.Changed, false)))
    assert(Mermaid.render(diff).contains("- table `account`: added column `email`"), Mermaid.render(diff))
  }
}
