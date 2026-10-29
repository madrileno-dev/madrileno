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
