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
}
