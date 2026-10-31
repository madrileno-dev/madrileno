package architecture

class ExtractorSuite extends munit.FunSuite {
  test("metrics count owned tables and events") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("user/repositories/R.scala", "package app.user.repositories\nobject UserRowTable extends Table[UserRow](\"user\")"),
        Fixtures.src(
          "user/domain/E.scala",
          "package app.user.domain\nobject E {\n  given DomainEventDescriptor[E] = DomainEventDescriptor(\"e.v1\", \"user\", _.id)\n}"
        )
      )
    )
    assertEquals(model.modules.size, 1)
    val user = model.modules.head
    assertEquals((user.metrics.tablesOwned, user.metrics.eventsOwned), (1, 1))
    assertEquals(model.warnings, Nil)
  }

  test("a *Module trait in main/ belongs to the composition root, not to a module of its own") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with SeedModule"),
        Fixtures.src("main/SeedModule.scala", "package app.main\ntrait SeedModule {\n  lazy val clock: Clock = Clock.system\n}"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  def clock: Clock\n}")
      )
    )
    assertEquals(model.modules.map(_.id), List("user"))
    assertEquals(model.warnings, Nil)
  }

  test("references explained by wiring: the member's type, its supertypes, its public signatures and wildcards of their layer") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends FlagModule with ShopModule"),
        Fixtures.src("flag/FlagModule.scala", "package app.flag\ntrait FlagModule {\n  lazy val flags: FlagServiceLive = ???\n}"),
        Fixtures.src(
          "flag/services/FlagService.scala",
          "package app.flag.services\ntrait FlagService\nenum FlagResult { case On, Off }\nclass FlagServiceLive extends FlagService {\n  def check(id: FlagId): IO[FlagResult] = ???\n  private def secret(x: Hidden): Unit = ()\n}\nobject FlagHelpers"
        ),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.flag.services.FlagServiceLive\ntrait ShopModule {\n  lazy val flags: FlagServiceLive\n}"
        ),
        Fixtures.src(
          "shop/services/S.scala",
          "package app.shop.services\nimport app.flag.services.{FlagService, FlagHelpers, FlagResult, Hidden}\nclass S(f: FlagService)"
        ),
        Fixtures.src("shop/services/T.scala", "package app.shop.services\nimport app.flag.services.*\nclass T")
      )
    )
    assertEquals(
      model.references.map(r => r.symbol -> r.explainedBy).sorted(using Ordering.by[(String, Option[Explanation]), String](_._1)),
      List(
        "*"               -> Some(Explanation.Wiring),
        "FlagHelpers"     -> None,
        "FlagResult"      -> Some(Explanation.Wiring),
        "FlagService"     -> Some(Explanation.Wiring),
        "FlagServiceLive" -> Some(Explanation.Wiring),
        "Hidden"          -> None
      )
    )
  }

  test("event types are explained wherever they live, and are not cake bypasses") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src(
          "user/services/UserGone.scala",
          "package app.user.services\nfinal case class UserGone(id: Id)\nobject UserGone {\n  given DomainEventDescriptor[UserGone] = DomainEventDescriptor(\"user-gone.v1\", \"user\", _.id)\n}"
        ),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.user.services.UserGone\ntrait ShopModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[UserGone](\"shop\")(h)\n}"
        )
      )
    )
    assertEquals(model.references.map(r => r.symbol -> r.explainedBy), List("UserGone" -> Some(Explanation.Event)))
    assertEquals(ArchMetrics.of(model).cakeBypasses, 0)
  }

  test("type arguments of a wired member are explained too") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends CacheModule with ShopModule"),
        Fixtures.src("cache/CacheModule.scala", "package app.cache\ntrait CacheModule {\n  lazy val cache: Cache[FooKey, FooValue] = ???\n}"),
        Fixtures.src("cache/services/Foo.scala", "package app.cache.services\nfinal case class FooKey(id: Int)\nfinal case class FooValue(v: Int)"),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.cache.services.{FooKey, FooValue}\ntrait ShopModule {\n  lazy val cache: Cache[FooKey, FooValue]\n}"
        )
      )
    )
    assertEquals(
      model.references.map(r => r.symbol -> r.explainedBy).sortBy(_._1),
      List("FooKey" -> Some(Explanation.Wiring), "FooValue" -> Some(Explanation.Wiring))
    )
  }
}
