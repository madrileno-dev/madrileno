package architecture

class TablesSuite extends munit.FunSuite {
  private val files = Fixtures.parsed(
    Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
    Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
    Fixtures.src(
      "user/repositories/UserRepository.scala",
      "package app.user.repositories\nobject UserRowTable extends Table[UserRow](\"\\\"user\\\"\")\nclass UserRepository {\n  def a = sql\"SELECT ${UserRowTable.*} FROM ${UserRowTable.n}\"\n}"
    ),
    Fixtures.src(
      "shop/repositories/ShopRepository.scala",
      "package app.shop.repositories\nobject OrderTable extends Table[OrderRow](\"order_line\")\nclass ShopRepository {\n  private val t = OrderTable\n  def a = sql\"SELECT ${t.*} FROM ${t.n}\"\n  def e = UserRowTable\n}"
    )
  )
  private val migrations = List(
    Fixtures.migration(
      "V1__init.sql",
      "create table \"user\"(id uuid);\nCREATE TABLE IF NOT EXISTS public.order_line(id uuid);\ncreate table ghost(id int);"
    )
  )
  private val result                    = TableExtractor.extract(files, migrations, Ownership.of(files))
  private val (tables, migrationTables) = result.value

  test("tables with normalized names, owners and users") {
    assertEquals(
      tables.map(t => (t.name, t.owner, t.users.map(_.enclosing).distinct)),
      List(("order_line", Owner.Module("shop"), List("ShopRepository")), ("user", Owner.Module("user"), List("UserRepository", "ShopRepository")))
    )
  }

  test("migration tables, with the last dot segment and line numbers") {
    assertEquals(migrationTables.map(m => m.name -> m.loc.line), List("user" -> 1, "order_line" -> 2, "ghost" -> 3))
  }

  test("warnings: cross-module use and undeclared migration table") {
    assertEquals(result.warnings.map(_.key).sorted, List("cross-module-table:user:shop", "migration-table-without-table:ghost"))
  }

  test("utils packages may use each other's tables, but a feature module using one still warns") {
    val files = Fixtures.parsed(
      Fixtures.src("utils/events/outbox/OutboxModule.scala", "package app.utils.events.outbox\ntrait OutboxModule"),
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
      Fixtures.src("utils/task/SchedulerRepository.scala", "package app.utils.task\nobject TaskRowTable extends Table[TaskRow](\"scheduled_task\")"),
      Fixtures.src(
        "utils/events/outbox/OutboxDeliveryRepository.scala",
        "package app.utils.events.outbox\nclass OutboxDeliveryRepository { def f = TaskRowTable }"
      ),
      Fixtures.src("shop/repositories/ShopRepository.scala", "package app.shop.repositories\nclass ShopRepository { def f = TaskRowTable }")
    )
    assertEquals(TableExtractor.extract(files, Nil, Ownership.of(files)).warnings.map(_.key), List("cross-module-table:scheduled_task:shop"))
  }

  test("a table two modules declare is one table with the users of both, and a warning") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
      Fixtures.src(
        "a/repositories/A.scala",
        "package app.a.repositories\nobject ATable extends Table[ARow](\"shared\")\nclass ARepository { def f = ATable }"
      ),
      Fixtures.src(
        "b/repositories/B.scala",
        "package app.b.repositories\nobject BTable extends Table[BRow](\"shared\")\nclass BRepository { def f = BTable }"
      )
    )
    val result = TableExtractor.extract(files, Nil, Ownership.of(files))
    assertEquals(
      result.value._1.map(t => (t.name, t.owner, t.users.map(_.enclosing))),
      List(("shared", Owner.Module("a"), List("ARepository", "BRepository")))
    )
    assertEquals(
      result.warnings.map(w => w.key -> w.message),
      List("duplicate-table:shared" -> "table shared is declared by ATable in a, BTable in b")
    )
  }

  test("two Table objects of one module for the same table are one table without a warning") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src(
        "a/repositories/A.scala",
        "package app.a.repositories\nobject ATable extends Table[ARow](\"shared\")\nobject ASummaryTable extends Table[ASummary](\"shared\")"
      )
    )
    val result = TableExtractor.extract(files, Nil, Ownership.of(files))
    assertEquals(result.value._1.map(_.name), List("shared"))
    assertEquals(result.warnings, Nil)
  }

  test("a table whose name is not a literal is unclassified") {
    val files = Fixtures.parsed(
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
      Fixtures.src("shop/repositories/R.scala", "package app.shop.repositories\nobject OrderTable extends Table[OrderRow](Names.Order)")
    )
    assertEquals(
      TableExtractor.extract(files, Nil, Ownership.of(files)).unclassified.map(_.key),
      List("table-name:shop/repositories/R.scala:OrderTable")
    )
  }
}
