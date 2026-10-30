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

  test("a table named by a named argument, and one cross-module warning when a module uses two declarations of a table") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
      Fixtures.src("c/CModule.scala", "package app.c\ntrait CModule"),
      Fixtures.src("a/repositories/A.scala", "package app.a.repositories\nobject ATable extends Table[ARow](tableName = \"shared\")"),
      Fixtures.src("b/repositories/B.scala", "package app.b.repositories\nobject BTable extends Table[BRow](\"shared\")"),
      Fixtures.src("c/repositories/C.scala", "package app.c.repositories\nclass CRepository { def f = (ATable, BTable) }")
    )
    val result = TableExtractor.extract(files, Nil, Ownership.of(files))
    assertEquals(result.unclassified, Nil)
    assertEquals(result.value._1.map(t => t.name -> t.users.map(_.enclosing)), List("shared" -> List("CRepository")))
    assertEquals(result.warnings.count(_.kind == WarningKind.CrossModuleTableUse), 1, result.warnings)
  }

  test("a Table object's columns, with their codecs") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/repositories/UserTable.scala",
        "package app.user.repositories\nobject UserTable extends Table[UserRow](\"user_account\") {\n" +
          "  val id: Column[UserId] = column(\"id\", uuid.as[UserId])\n" +
          "  val email = column(\n    name = \"email\",\n    codec = text.as[Email] // validated upstream\n      .opt\n  )\n}"
      )
    )
    assertEquals(
      TableExtractor.extract(files, Nil, Ownership.of(files)).value._1.map(_.columns),
      List(List(TableColumn("id", "uuid.as[UserId]"), TableColumn("email", "text.as[Email].opt")))
    )
  }

  test("a migration's digest ignores comments and formatting, and it knows the tables it creates, alters and drops") {
    val sql = List(
      Fixtures.migration(
        "V1__init.sql",
        "-- users\ncreate table if not exists \"User_Account\" (\n  id uuid primary key\n);\nALTER TABLE ONLY public.auth ADD COLUMN x text; /* later */\ndrop table if exists old_a, old_b;"
      ),
      Fixtures.migration(
        "V2__same.sql",
        "create table if not exists \"User_Account\" (id uuid primary key);\nALTER TABLE ONLY public.auth ADD COLUMN x text;\ndrop table if exists old_a, old_b;"
      )
    )
    val migrations = MigrationExtractor.extract(sql)
    assertEquals(
      migrations.map(m => (m.path, m.creates, m.alters, m.drops)),
      List(
        ("V1__init.sql", List("user_account"), List("auth"), List("old_a", "old_b")),
        ("V2__same.sql", List("user_account"), List("auth"), List("old_a", "old_b"))
      )
    )
    assertEquals(migrations.map(_.digest).distinct.size, 1)
  }

  test("a CREATE TABLE in a comment or for a partition is not a migration table, and an unlogged one is") {
    val sql = Fixtures.migration(
      "V1__init.sql",
      "-- create table for bookings\n/* create table legacy (\n   id int); */\ncreate unlogged table cache_entries (k text);\n" +
        "create table orders_2024 partition of orders for values from ('2024-01-01') to ('2025-01-01');\n" +
        "create table if not exists orders_2025 partition of orders for values from ('2025-01-01') to ('2026-01-01');"
    )
    val result = TableExtractor.extract(Nil, List(sql), Ownership(Set.empty))
    assertEquals(result.value._2.map(t => t.name -> t.loc.line), List("cache_entries" -> 4))
    assertEquals(result.warnings.map(_.key), List("migration-table-without-table:cache_entries"))
    assertEquals(MigrationExtractor.extract(List(sql)).map(_.creates), List(List("cache_entries")))
  }

  test("a schema-qualified Table name matches its migration") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src("user/repositories/UserTable.scala", "package app.user.repositories\nobject UserTable extends Table[UserRow](\"public.users\")")
    )
    val result = TableExtractor.extract(files, List(Fixtures.migration("V1__init.sql", "create table public.users (id uuid);")), Ownership.of(files))
    assertEquals(result.value._1.map(_.name), List("users"))
    assertEquals(result.warnings, Nil)
  }
  test("an object extending a subclass of Table is a table, with the name the subclass passes on") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "utils/db/AuditedTable.scala",
        "package app.utils.db\nabstract class AuditedTable[R](schema: String, name: String) extends Table[R](name)\nabstract class UsersTable extends AuditedTable[UserRow](\"app\", \"users\")"
      ),
      Fixtures.src(
        "user/repositories/Tables.scala",
        "package app.user.repositories\nobject AccountTable extends AuditedTable[AccountRow](\"app\", \"account\")\nobject UserTable extends UsersTable\n" +
          "class UserRepository {\n  def a = AccountTable\n}"
      )
    )
    val migrations = List(Fixtures.migration("V1__init.sql", "create table account (id uuid);\ncreate table users (id uuid);"))
    val result     = TableExtractor.extract(files, migrations, Ownership.of(files))
    assertEquals(
      result.value._1.map(t => (t.name, t.objectName, t.users.map(_.enclosing))),
      List(("account", "AccountTable", List("UserRepository")), ("users", "UserTable", Nil))
    )
    assertEquals(result.warnings, Nil)
    assertEquals(result.unclassified, Nil)
  }

  test("a Table subclass whose name the map can't follow is unclassified") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src("utils/db/Named.scala", "package app.utils.db\nabstract class Named[R] extends Table[R](Names.next())"),
      Fixtures.src("user/repositories/UserTable.scala", "package app.user.repositories\nobject UserTable extends Named[UserRow]")
    )
    assertEquals(
      TableExtractor.extract(files, Nil, Ownership.of(files)).unclassified.map(u => u.key -> u.description),
      List("table-name:user/repositories/UserTable.scala:UserTable" -> "UserTable extends Table with the name Names.next(), not a string literal")
    )
  }
}
