package architecture

class ModuleExtractorSuite extends munit.FunSuite {
  private val files = Fixtures.parsed(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends Base with UserModule"),
    Fixtures.src(
      "user/UserModule.scala",
      """package app.user
        |trait UserModule extends AuthRouteProvider with MailPreviewProvider {
        |  given telemetryContext: TelemetryContext
        |  val transactor: Transactor
        |  lazy val outbox: Outbox
        |  lazy val userRepository: UserRepository = wire[UserRepository]
        |  protected lazy val clock: Clock = Clock.system
        |  private lazy val userService = wire[UserService]
        |  private lazy val userRouter = new UserRouter(userService)
        |  override abstract def route(auth: AuthContext): Route = {
        |    super.route(auth) ~ userRouter.authedRoutes(auth)
        |  }
        |  override abstract def mailPreviews: List[MailPreview] = {
        |    super.mailPreviews :+ A.preview :+ B.preview
        |  }
        |}""".stripMargin
    ),
    Fixtures.src("user/repositories/UserRepository.scala", "package app.user.repositories\nclass UserRepository"),
    Fixtures.src(
      "user/services/UserService.scala",
      "package app.user.services\nclass UserService(userRepository: UserRepository, outbox: Outbox)(using TelemetryContext)"
    ),
    Fixtures.src("user/routers/UserRouter.scala", "package app.user.routers\nclass UserRouter(userService: UserService)"),
    Fixtures.src("billing/BillingModule.scala", "package app.billing\ntrait BillingModule {\n  private val x = wire[Missing]\n}"),
    Fixtures.src("utils/http/Routes.scala", "package app.utils.http\ntrait AuthRouteProvider")
  )
  private val ownership = Ownership.of(files)
  private val result    = ModuleExtractor.extract(files, ownership)
  private val user      = result.value.find(_.id == "user").get

  test("ownership resolves modules, platform and the composition root") {
    assertEquals(ownership.ownerOf("user/services"), Owner.Module("user"))
    assertEquals(ownership.ownerOf("utils/http"), Owner.Platform("utils/http"))
    assertEquals(ownership.ownerOf("main"), Owner.CompositionRoot)
  }

  test("consumes and provides") {
    assertEquals(
      user.consumes.map(m => m.name -> m.tpe),
      List("telemetryContext" -> "TelemetryContext", "transactor" -> "Transactor", "outbox" -> "Outbox")
    )
    assertEquals(user.provides.map(m => m.name -> m.tpe), List("userRepository" -> "UserRepository", "clock" -> "Clock"))
  }

  test("contributions keep only the appended operands") {
    assertEquals(
      user.contributions.map(c => c.registry -> c.items),
      List(Registry.AuthRoutes -> List("userRouter.authedRoutes(auth)"), Registry.MailPreviews -> List("A.preview", "B.preview"))
    )
  }

  test("components with kinds and constructor dependencies") {
    assertEquals(
      user.components.map(c => (c.name, c.kind, c.dependencies)),
      List(
        ("UserRepository", ComponentKind.Repository, Nil),
        ("UserService", ComponentKind.Service, List("UserRepository", "Outbox")),
        ("UserRouter", ComponentKind.Router, List("UserService"))
      )
    )
  }

  test("mixedIn and unresolved components") {
    assertEquals(result.value.map(m => m.id -> m.mixedIn), List("billing" -> false, "user" -> true))
    assertEquals(result.unclassified.map(_.key), List("component:billing:Missing"))
  }

  test("lines of code count non-blank lines of the module's files") {
    assert(user.metrics.linesOfCode >= 18)
    assertEquals(user.metrics.components, 3)
  }

  test("a second module trait in the same package is unclassified and the mixed-in one is kept") {
    val files = Fixtures.parsed(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule"),
      Fixtures.src("shop/ShopAdminModule.scala", "package app.shop\ntrait ShopAdminModule {\n  lazy val admin: Admin = ???\n}"),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule {\n  lazy val shop: Shop = ???\n}")
    )
    val result = ModuleExtractor.extract(files, Ownership.of(files))
    assertEquals(result.value.map(m => m.id -> m.traitName), List("shop" -> "ShopModule"))
    assertEquals(result.unclassified.map(_.key), List("module-duplicate:shop:ShopAdminModule"))
  }
}
