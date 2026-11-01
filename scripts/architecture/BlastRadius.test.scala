package architecture

class BlastRadiusSuite extends munit.FunSuite {
  private def traitOf(id: String) = s"${id.capitalize}Module"

  private def modules(size: String, others: (String, List[String])*): ProjectInput = {
    val ids = "a" :: others.map(_._1).toList
    Fixtures.project(
      (List(
        Fixtures.src("main/ApplicationLoader.scala", s"package app.main\nclass ApplicationLoader extends ${ids.map(traitOf).mkString(" with ")}"),
        Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
        Fixtures.src("a/domain/Thing.scala", s"package app.a.domain\nclass Thing {\n  def size = $size\n}")
      ) ++ others.map((id, uses) =>
        Fixtures
          .src(s"$id/${traitOf(id)}.scala", s"package app.$id\n${uses.map(used => s"import app.$used.domain.Thing\n").mkString}trait ${traitOf(id)}")
      ))*
    )
  }

  private def compare(base: ProjectInput, head: ProjectInput): DiffModel =
    ArchDiff.compare(Extractor.extract(base), Extractor.extract(head), "main", "working tree")

  test("a code change in a module that half or more of the others depend on is high, and names them") {
    val others = List("b" -> List("a"), "c" -> List("a"), "d" -> Nil)
    val diff   = compare(modules("1", others*), modules("2", others*))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.changed, radius.reached, radius.others), (BlastLevel.High, List("a"), List("b", "c"), 3))
    val text = Mermaid.render(diff)
    assert(
      text.startsWith(
        "No architecture changes between `main` and `working tree`.\n\n**Blast radius: high.** 2 of the 3 other modules depend on what changed: `b`, `c`.\n"
      ),
      text
    )
    assert(
      Html
        .render(diff)
        .contains(
          """<div class="metrics"><div class="metric"><div class="metric-name">Blast radius</div><div class="metric-value">High</div><div class="muted">2 of the 3 other modules depend on what changed: <code>b</code>, <code>c</code>.</div></div>"""
        )
    )
  }

  test("modules that depend on a dependent are reached too, and fewer than half of the others is medium") {
    val others = List("b" -> List("a"), "c" -> List("b"), "d" -> Nil, "e" -> Nil, "f" -> Nil)
    val radius = compare(modules("1", others*), modules("2", others*)).blastRadius.get
    assertEquals((radius.level, radius.reached, radius.others), (BlastLevel.Medium, List("b", "c"), 5))
  }

  test("a change no other module depends on is low, and code outside the modules is mentioned but not counted") {
    def app(size: String, retries: String) = Fixtures.project(
      (modules(size, "b" -> Nil).scalaFiles :+ Fixtures
        .src("utils/task/Scheduler.scala", s"package app.utils.task\nclass Scheduler {\n  def retries = $retries\n}"))*
    )
    val diff   = compare(app("1", "3"), app("2", "5"))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.reached, radius.outsideChanged), (BlastLevel.Low, Nil, true))
    assert(
      Mermaid
        .render(diff)
        .contains(
          "**Blast radius: low.** No other module depends on what changed. Code outside the modules changed too, which this doesn't trace.\n"
        ),
      Mermaid.render(diff)
    )
  }

  test("a changed table reaches the modules that use it, and adding a column isn't hard to undo") {
    def app(migrations: List[SourceFile]) = Fixtures.projectWithMigrations(
      List(
        Fixtures.src(
          "main/ApplicationLoader.scala",
          "package app.main\nclass ApplicationLoader extends UserModule with ShopModule with CModule with DModule"
        ),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
        Fixtures.src("c/CModule.scala", "package app.c\ntrait CModule"),
        Fixtures.src("d/DModule.scala", "package app.d\ntrait DModule"),
        Fixtures.src(
          "user/repositories/AccountTable.scala",
          "package app.user.repositories\nobject AccountTable extends Table[Account](\"account\") {\n  val id = column(\"id\", uuid)\n}"
        ),
        Fixtures.src("shop/repositories/ShopRepository.scala", "package app.shop.repositories\nclass ShopRepository {\n  def f = AccountTable\n}")
      ),
      migrations
    )
    val v1     = Fixtures.migration("V1__init.sql", "create table account (id uuid primary key);")
    val v2     = Fixtures.migration("V2__nick.sql", "alter table account add column nick text;")
    val radius = compare(app(List(v1)), app(List(v1, v2))).blastRadius.get
    assertEquals((radius.level, radius.changed, radius.reached, radius.irreversible), (BlastLevel.Medium, Nil, List("shop"), Nil))
  }

  test("a migration knows what it does to existing tables that's hard to undo, and adding to them isn't") {
    val sql = List(
      "create table note (id uuid);",
      "alter table account add column nick text, drop column email, rename column name to full_name;",
      "alter table account alter column age type bigint, alter column age drop not null, drop constraint account_pk;",
      "alter table orders rename to purchases;",
      "drop table legacy;",
      "update account set nick = 'x; drop table account';",
      "delete from sessions;",
      "truncate audit;",
      "insert into account values (1);",
      "delete from note;",
      "create index on account (nick);"
    ).mkString("\n")
    assertEquals(
      MigrationExtractor.extract(List(Fixtures.migration("V7__cleanup.sql", sql))).flatMap(_.destructive),
      List(
        Destructive("drops column", "account.email"),
        Destructive("renames column", "account.name"),
        Destructive("changes the type of", "account.age"),
        Destructive("renames table", "orders"),
        Destructive("drops table", "legacy"),
        Destructive("updates rows in", "account"),
        Destructive("deletes rows from", "sessions"),
        Destructive("truncates", "audit")
      )
    )
  }

  test("an edited or removed migration is hard to undo, and an added one for what it destroys") {
    def app(migrations: SourceFile*) = Fixtures.projectWithMigrations(modules("1", "b" -> Nil).scalaFiles, migrations.toList)
    val v1                           = Fixtures.migration("V1__init.sql", "create table account (id uuid primary key, email text);")
    val v2                           = Fixtures.migration("V2__notes.sql", "create table notes (id uuid);")
    val diff                         = compare(
      app(v1, v2),
      app(
        Fixtures.migration("V1__init.sql", "-- accounts\ncreate table account (id uuid primary key, email text);"),
        Fixtures.migration("V3__drop.sql", "alter table account drop column email;")
      )
    )
    val radius = diff.blastRadius.get
    assertEquals(
      radius.irreversible,
      List(
        Irreversible.EditedMigration("V1__init.sql"),
        Irreversible.RemovedMigration("V2__notes.sql"),
        Irreversible.Migrated("V3__drop.sql", "drops column", "account.email")
      )
    )
    assertEquals(radius.level, BlastLevel.High)
    assert(
      Mermaid
        .render(diff)
        .contains(
          "**Blast radius: high.** No module changed. Hard to undo: `V1__init.sql` was edited, though it may already have run; `V2__notes.sql` was removed, though it may already have run; `V3__drop.sql` drops column `account.email`.\n"
        ),
      Mermaid.render(diff)
    )
  }

  test("an outbox event whose payload type or companion changed is hard to undo, and a change elsewhere in its file isn't") {
    def app(
      payload: String,
      companion: String = "",
      rest: String = ""
    ) = Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        s"package app.user.domain\nfinal case class UserDeleted($payload)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n$companion}\n$rest"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
      )
    )
    val payload = List(Irreversible.Payload("user-deleted.v1"))
    assertEquals(compare(app("id: Id"), app("id: Id, reason: String")).blastRadius.get.irreversible, payload)
    assertEquals(
      compare(app("id: Id"), app("id: Id", companion = "  given Encoder[UserDeleted] = deriveEncoder\n")).blastRadius.get.irreversible,
      payload
    )
    assertEquals(compare(app("id: Id"), app("id: Id", rest = "class Helper")).blastRadius.get.irreversible, Nil)
    assert(
      Mermaid
        .render(compare(app("id: Id"), app("id: Id, reason: String")))
        .contains("Hard to undo: the payload of outbox event `user-deleted.v1` changed, and stored rows keep the old shape.")
    )
  }

  test("the full map has no blast radius") {
    val full = ArchDiff.full(Extractor.extract(modules("1", "b" -> List("a"))), "working tree")
    assertEquals(full.blastRadius, None)
    assert(!Html.render(full).contains("Blast radius"))
  }
}
