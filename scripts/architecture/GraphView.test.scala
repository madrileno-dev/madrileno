package architecture

class GraphViewSuite extends munit.FunSuite {
  private val model = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
      Fixtures
        .src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = wire[UserRepository]\n}"),
      Fixtures.src(
        "user/repositories/UserRepository.scala",
        "package app.user.repositories\nobject UserRowTable extends Table[UserRow](\"user\")\nclass UserRepository { def f = UserRowTable }"
      ),
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n  private val s = wire[ShopService]\n}"
      ),
      Fixtures.src("shop/services/ShopService.scala", "package app.shop.services\nclass ShopService(r: UserRepository)")
    )
  )
  private val diff = ArchDiff.full(model, "h")

  private def withOutbox(outboxModule: String) = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with OutboxModule"),
    Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val outbox: Outbox\n}"),
    Fixtures.src("utils/events/outbox/OutboxModule.scala", outboxModule),
    Fixtures.src(
      "utils/events/outbox/OutboxRepository.scala",
      "package app.utils.events.outbox\nobject DomainEventTable extends Table[DomainEvent](\"domain_event\")\nclass OutboxRepository { def f = DomainEventTable }"
    )
  )
  private val outboxModule = "package app.utils.events.outbox\ntrait OutboxModule {\n  lazy val outbox: Outbox = wire[Outbox]\n}"

  test("utils modules stay off the diagram, with their edges and the tables only they use") {
    val graph = GraphView.full(ArchDiff.full(Extractor.extract(withOutbox(outboxModule)), "h"), Level.Everything)
    assertEquals(graph.nodes.map(_.key), List("module:user"))
    assertEquals(graph.edges, Nil)
  }

  test("a utils module a diff changes is shown") {
    val changed = outboxModule.replace("}", "  lazy val outboxRecovery: OutboxRecovery = wire[OutboxRecovery]\n}")
    val diff    = ArchDiff.compare(Extractor.extract(withOutbox(outboxModule)), Extractor.extract(withOutbox(changed)), "b", "h")
    assert(GraphView.changes(diff).nodes.exists(_.key == "module:utils/events/outbox"))
    assertEquals(GraphView.full(diff, Level.Modules).nodes.map(_.key), List("module:user", "module:utils/events/outbox"))
    assertEquals(GraphView.shown(diff).map(_.id), List("user", "utils/events/outbox"))
  }

  test("the modules level shows modules and the dependencies between them") {
    val graph = GraphView.full(diff, Level.Modules)
    assertEquals(graph.nodes.map(_.key), List("module:shop", "module:user"))
    assertEquals(graph.edges.map(e => e.from -> e.to), List("module:shop" -> "module:user"))
  }

  test("the components level adds services and repositories inside their modules") {
    val graph = GraphView.full(diff, Level.Components)
    assertEquals(
      graph.nodes.filter(_.kind == NodeKind.Component).map(n => n.key -> n.parent),
      List("component:shop:ShopService" -> Some("module:shop"), "component:user:UserRepository" -> Some("module:user"))
    )
  }

  test("the everything level adds tables and events with their edges") {
    val graph = GraphView.full(diff, Level.Everything)
    assert(graph.nodes.exists(_.key == "table:user"))
    assert(graph.edges.exists(e => e.from == "module:user" && e.to == "table:user"))
  }

  private val web = ArchDiff.full(
    Extractor.extract(
      Fixtures.project(
        Fixtures
          .src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with CModule with DModule"),
        Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule {\n  private val s = wire[AService]\n}"),
        Fixtures.src(
          "a/services/AService.scala",
          "package app.a.services\nclass AService(outbox: Outbox) {\n  def p(id: Id) = outbox.publishTransactionally(Gone(id))\n}"
        ),
        Fixtures.src(
          "a/domain/Gone.scala",
          "package app.a.domain\nobject Gone {\n  given DomainEventDescriptor[Gone] = DomainEventDescriptor(\"gone.v1\", \"a\", _.id)\n}"
        ),
        Fixtures.src("b/BModule.scala", "package app.b\nimport app.d.domain.Y\ntrait BModule {\n  private val s = wire[BService]\n}"),
        Fixtures.src("b/services/BService.scala", "package app.b.services\nclass BService"),
        Fixtures.src("c/CModule.scala", "package app.c\nimport app.a.domain.Z\nimport app.b.domain.W\ntrait CModule"),
        Fixtures.src(
          "d/DModule.scala",
          "package app.d\ntrait DModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Gone](\"d\")(h)\n}"
        )
      )
    ),
    "h"
  )

  test("a focused module shows its direct neighbours and only the edges that touch it") {
    val graph = GraphView.focus(web, "a", Level.Modules)
    assertEquals(graph.nodes.map(_.key).toSet, Set("module:a", "module:b", "module:c"))
    assertEquals(graph.edges.map(e => e.from -> e.to).toSet, Set("module:a" -> "module:b", "module:c" -> "module:a"))
  }

  test("only the focused module opens up into its services and repositories") {
    val graph = GraphView.focus(web, "a", Level.Components)
    assertEquals(graph.nodes.filter(_.kind == NodeKind.Component).map(_.key), List("component:a:AService"))
  }

  test("with tables and events, the modules on the other side of its events join the focus") {
    val graph = GraphView.focus(web, "a", Level.Everything)
    assert(graph.nodes.exists(_.key == "module:d"), graph.nodes)
    assert(graph.edges.exists(e => e.from == "event:gone.v1" && e.to == "module:d"), graph.edges)
    assert(!graph.edges.exists(e => e.from == "module:b" && e.to == "module:d"), graph.edges)
  }
}
