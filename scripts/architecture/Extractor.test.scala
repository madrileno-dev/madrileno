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

  test("references covered by wiring are marked: the member's type, its supertypes and wildcards of their layer") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends FlagModule with ShopModule"),
        Fixtures.src("flag/FlagModule.scala", "package app.flag\ntrait FlagModule {\n  lazy val flags: FlagServiceLive = ???\n}"),
        Fixtures.src(
          "flag/services/FlagService.scala",
          "package app.flag.services\ntrait FlagService\nclass FlagServiceLive extends FlagService\nobject FlagHelpers"
        ),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.flag.services.FlagServiceLive\ntrait ShopModule {\n  lazy val flags: FlagServiceLive\n}"
        ),
        Fixtures.src(
          "shop/services/S.scala",
          "package app.shop.services\nimport app.flag.services.FlagService\nimport app.flag.services.FlagHelpers\nclass S(f: FlagService)"
        ),
        Fixtures.src("shop/services/T.scala", "package app.shop.services\nimport app.flag.services.*\nclass T")
      )
    )
    assertEquals(
      model.references.map(r => r.symbol -> r.wired).sorted,
      List("*" -> true, "FlagHelpers" -> false, "FlagService" -> true, "FlagServiceLive" -> true)
    )
  }
}
