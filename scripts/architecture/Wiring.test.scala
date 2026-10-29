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
}
