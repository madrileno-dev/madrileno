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

  test("lines of code count the lines of the module's files that hold code") {
    assert(user.metrics.linesOfCode >= 18)
    assertEquals(user.metrics.components, 3)
  }

  test("lines of code leave out comments and blank lines, and count every line of a multi-line string") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "notes/NotesModule.scala",
        "package app.notes\n\n// a line comment\n/** scaladoc\n  * over lines\n  */\ntrait NotesModule {\n  /* block */\n  val sql = \"\"\"SELECT 1\n    FROM t\"\"\" // trailing\n}\n"
      )
    )
    assertEquals(ModuleExtractor.extract(files, Ownership.of(files)).value.map(_.metrics.linesOfCode), List(5))
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

  test("module traits in one package that are all mixed in are merged into one module") {
    val files = Fixtures.parsed(
      Fixtures
        .src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule with ShopAdminModule with BillingModule"),
      Fixtures.src(
        "shop/ShopAdminModule.scala",
        "package app.shop\ntrait ShopAdminModule {\n  lazy val admin: Admin = ???\n  private val x = wire[Missing]\n}"
      ),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule {\n  lazy val shopService: ShopService = ???\n}"),
      Fixtures.src("billing/BillingModule.scala", "package app.billing\ntrait BillingModule {\n  lazy val shopService: ShopService\n}")
    )
    val ownership = Ownership.of(files)
    val result    = ModuleExtractor.extract(files, ownership)
    val shop      = result.value.find(_.id == "shop").get
    assertEquals(shop.traitName, "ShopAdminModule + ShopModule")
    assertEquals(shop.provides.map(_.name).sorted, List("admin", "shopService"))
    assertEquals(result.unclassified.map(_.key), List("component:shop:Missing"))
    assertEquals(WiringExtractor.extract(result.value, files, ownership).warnings, Nil)
  }

  test("a member one merged module trait declares and the other defines is not consumed") {
    val files = Fixtures.parsed(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule with ShopAdminModule"),
      Fixtures.src("shop/ShopAdminModule.scala", "package app.shop\ntrait ShopAdminModule {\n  lazy val shopService: ShopService\n}"),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule {\n  lazy val shopService: ShopService = ???\n}")
    )
    val ownership = Ownership.of(files)
    val result    = ModuleExtractor.extract(files, ownership)
    assertEquals(result.value.map(_.consumes), List(Nil))
    assertEquals(WiringExtractor.extract(result.value, files, ownership).warnings, Nil)
  }

  test("modules mixed in through a grouping trait are mixed in") {
    val files = Fixtures.parsed(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends FeatureModules"),
      Fixtures.src("main/FeatureModules.scala", "package app.main\ntrait FeatureModules extends AModule with BModule"),
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule")
    )
    val result = ModuleExtractor.extract(files, Ownership.of(files))
    assertEquals(result.value.map(m => m.id -> m.mixedIn), List("a" -> true, "b" -> true))
    assertEquals(result.warnings, Nil)
  }
  test("a component built with its class name and no new is a component, and an anonymous given provides nothing") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "billing/BillingModule.scala",
        "package app.billing\ntrait BillingModule {\n  lazy val billingService: BillingService = BillingService(transactor)\n  lazy val config = BillingConfig(1)\n" +
          "  given Ordering[Int] = Ordering.Int\n}"
      ),
      Fixtures.src("billing/services/BillingService.scala", "package app.billing.services\nclass BillingService(transactor: Transactor)"),
      Fixtures.src("billing/domain/BillingConfig.scala", "package app.billing.domain\nfinal case class BillingConfig(days: Int)")
    )
    val billing = ModuleExtractor.extract(files, Ownership.of(files)).value.head
    assertEquals(billing.components.map(c => (c.name, c.kind)), List(("BillingService", ComponentKind.Service)))
    assertEquals(billing.provides.map(_.name), List("billingService", "config"))
  }

  test("an implicit parameter clause is not a dependency, like a using clause") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  private lazy val s = wire[S]\n}"),
      Fixtures.src("user/services/S.scala", "package app.user.services\nclass S(repo: Repo)(implicit tx: Transactor)")
    )
    assertEquals(ModuleExtractor.extract(files, Ownership.of(files)).value.flatMap(_.components.map(_.dependencies)), List(List("Repo")))
  }
}
