package architecture

class WiringSuite extends munit.FunSuite {
  private val files = Fixtures.parsed(
    Fixtures.src(
      "main/ApplicationLoader.scala",
      "package app.main\nimport app.user.domain.*\nclass ApplicationLoader extends AuthModule with UserModule {\n  val transactor: Transactor = ???\n}"
    ),
    Fixtures.src("utils/http/Routes.scala", "package app.utils.http\nimport app.auth.domain.AuthContext\ntrait AuthRouteProvider"),
    Fixtures.src(
      "auth/AuthModule.scala",
      "package app.auth\nimport app.user.repositories.UserRepository\ntrait AuthModule {\n  val transactor: Transactor\n  lazy val userRepository: UserRepository\n  lazy val ghost: Ghost\n  lazy val authService: AuthService = ???\n}"
    ),
    Fixtures.src(
      "user/UserModule.scala",
      "package app.user\nimport app.auth.domain.AuthContext\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"
    ),
    Fixtures.src("user/routers/R.scala", "package app.user.routers\nclass R { def f(x: app.auth.services.AuthService) = x }")
  )
  private val ownership  = Ownership.of(files)
  private val modules    = ModuleExtractor.extract(files, ownership).value
  private val wiring     = WiringExtractor.extract(modules, files, ownership)
  private val references = ReferenceExtractor.extract(files, ownership, "app")

  test("members wire to modules or platform, and the rest warn") {
    assertEquals(
      wiring.value.toSet,
      Set(Wiring("transactor", "auth", Provider.FromPlatform), Wiring("userRepository", "auth", Provider.FromModule("user")))
    )
    assertEquals(wiring.warnings.map(_.key), List("unprovided:auth:ghost"))
  }

  test("constructor val parameters of the composition root provide members, plain parameters do not") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "main/ApplicationLoader.scala",
        "package app.main\nclass ApplicationLoader(val transactor: Transactor, backend: Backend) extends UserModule"
      ),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  val transactor: Transactor\n  val backend: Backend\n}")
    )
    val ownership = Ownership.of(files)
    val result    = WiringExtractor.extract(ModuleExtractor.extract(files, ownership).value, files, ownership)
    assertEquals(result.value, List(Wiring("transactor", "user", Provider.FromPlatform)))
    assertEquals(result.warnings.map(_.key), List("unprovided:user:backend"))
  }

  test("platform-contract types and composition-root imports are not references, FQNs are") {
    assertEquals(
      references.map(r => (r.from, r.to, r.layer, r.symbol)),
      List(("auth", "user", "repositories", "UserRepository"), ("user", "auth", "services", "AuthService"))
    )
  }

  test("a mutual dependency is one cycle warning") {
    assertEquals(Cycles.warnings(modules, wiring.value, references).map(_.key), List("cycle:auth,user"))
  }

  test("no cycle when the dependency goes one way") {
    assertEquals(Cycles.warnings(modules, wiring.value, references.filterNot(_.from == "user")), Nil)
  }

  test("imports relative to the root under chained package clauses are references") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
      Fixtures.src("shop/services/S.scala", "package app\npackage shop.services\nimport user.repositories.UserRepository\nclass S")
    )
    assertEquals(
      ReferenceExtractor.extract(files, Ownership.of(files), "app").map(r => (r.from, r.to, r.layer, r.symbol)),
      List(("shop", "user", "repositories", "UserRepository"))
    )
  }

  test("only ApplicationLoader and the platform traits it extends provide members") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "main/ApplicationLoader.scala",
        "package app.main\nclass ApplicationLoader extends Logging with UserModule {\n  val transactor: Transactor = ???\n}"
      ),
      Fixtures.src("main/SeedMain.scala", "package app.main\nobject SeedMain {\n  val seeds: Seeds = ???\n}"),
      Fixtures.src("utils/log/Logging.scala", "package app.utils.log\ntrait Logging extends Base\ntrait Base {\n  def logger: Logger = ???\n}"),
      Fixtures.src("utils/http/AdminRouter.scala", "package app.utils.http\nclass AdminRouter {\n  val routes: Routes = ???\n}"),
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  val transactor: Transactor\n  val logger: Logger\n  val routes: Routes\n  val seeds: Seeds\n}"
      )
    )
    val ownership = Ownership.of(files)
    val result    = WiringExtractor.extract(ModuleExtractor.extract(files, ownership).value, files, ownership)
    assertEquals(result.value.map(_.member).sorted, List("logger", "transactor"))
    assertEquals(result.warnings.map(_.key).sorted, List("unprovided:user:routes", "unprovided:user:seeds"))
  }
}
