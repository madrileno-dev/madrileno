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
}
