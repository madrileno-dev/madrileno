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

  private def leaves(sizes: (String, String)*): ProjectInput = Fixtures.project(
    (Fixtures.src(
      "main/ApplicationLoader.scala",
      s"package app.main\nclass ApplicationLoader extends ${sizes.map((id, _) => traitOf(id)).mkString(" with ")}"
    ) +:
      sizes.flatMap((id, size) =>
        List(
          Fixtures.src(s"$id/${traitOf(id)}.scala", s"package app.$id\ntrait ${traitOf(id)}"),
          Fixtures.src(s"$id/domain/Thing.scala", s"package app.$id.domain\nclass Thing {\n  def size = $size\n}")
        )
      ))*
  )

  private def app(ids: String*)(files: SourceFile*): ProjectInput = Fixtures.project(
    (Fixtures.src("main/ApplicationLoader.scala", s"package app.main\nclass ApplicationLoader extends ${ids.map(traitOf).mkString(" with ")}") +:
      (ids.map(id => Fixtures.src(s"$id/${traitOf(id)}.scala", s"package app.$id\ntrait ${traitOf(id)}")) ++ files))*
  )

  private def compare(base: ProjectInput, head: ProjectInput): DiffModel =
    ArchDiff.compare(Extractor.extract(base), Extractor.extract(head), "main", "working tree")

  test("a code change that reaches half or more of the modules with the changed one is high, and names them") {
    val others = List("b" -> List("a"), "c" -> List("a"), "d" -> Nil)
    val diff   = compare(modules("1", others*), modules("2", others*))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.changed, radius.reached, radius.modules), (BlastLevel.High, List("a"), List("b", "c"), 4))
    val text = Mermaid.render(diff)
    assert(
      text.startsWith(
        "No architecture changes between `main` and `working tree`.\n\n**Blast radius: high.** It changes `a`. 2 of the 3 other modules depend on what changed: `b`, `c`.\n"
      ),
      text
    )
    assert(
      Html
        .render(diff)
        .contains(
          """<div class="metrics"><div class="metric"><div class="metric-name">Blast radius</div><div class="metric-value">High</div><div class="muted">It changes <code>a</code>. 2 of the 3 other modules depend on what changed: <code>b</code>, <code>c</code>.</div></div>"""
        )
    )
  }

  test("a module that depends only on a dependent isn't reached, and reaching fewer than half of the modules is medium") {
    val others = List("b" -> List("a"), "c" -> List("b"), "d" -> Nil, "e" -> Nil, "f" -> Nil)
    val radius = compare(modules("1", others*), modules("2", others*)).blastRadius.get
    assertEquals((radius.level, radius.reached, radius.modules), (BlastLevel.Medium, List("b"), 6))
  }

  test("the changed modules count toward the share, and two changed modules are medium even when nothing depends on them") {
    val ids                   = List("a", "b", "c", "d", "e", "f")
    def app(changed: String*) = leaves(ids.map(id => id -> (if (changed.contains(id)) "2" else "1"))*)
    val three                 = compare(app(), app("a", "b", "c"))
    assertEquals((three.blastRadius.get.level, three.blastRadius.get.changed), (BlastLevel.High, List("a", "b", "c")))
    assert(
      Mermaid
        .render(three)
        .contains("**Blast radius: high.** It changes 3 of the 6 modules (`a`, `b`, `c`). No other module depends on what changed.\n"),
      Mermaid.render(three)
    )
    assertEquals(compare(app(), app("a", "b")).blastRadius.get.level, BlastLevel.Medium)
    assertEquals(compare(app(), app("a")).blastRadius.get.level, BlastLevel.Low)
  }

  test("added modules count toward neither the share nor the total it's measured against") {
    val base   = List("a", "b", "c", "d", "e").map(_ -> "1")
    val head   = base.map((id, size) => id -> (if (Set("a", "b", "c")(id)) "2" else size)) ++ List("n1" -> "1", "n2" -> "1")
    val diff   = compare(leaves(base*), leaves(head*))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.modules), (BlastLevel.High, 5))
    assert(
      Mermaid.render(diff).contains("**Blast radius: high.** It changes 3 of the 5 modules (`a`, `b`, `c`) and adds 2 modules (`n1`, `n2`)."),
      Mermaid.render(diff)
    )
  }

  test("when every other module depends on what changed, the summary says so") {
    val two = compare(modules("1", "b" -> List("a")), modules("2", "b" -> List("a")))
    assert(Mermaid.render(two).contains("It changes `a`. The other module depends on what changed: `b`.\n"), Mermaid.render(two))
    val all = compare(modules("1", "b" -> List("a"), "c" -> List("a")), modules("2", "b" -> List("a"), "c" -> List("a")))
    assert(Mermaid.render(all).contains("It changes `a`. All 2 other modules depend on what changed: `b`, `c`.\n"), Mermaid.render(all))
  }

  test("a change to every module says so instead of saying that no other module depends on it") {
    val all = compare(leaves("a" -> "1", "b" -> "1", "c" -> "1"), leaves("a" -> "2", "b" -> "2", "c" -> "2"))
    assertEquals(all.blastRadius.get.level, BlastLevel.High)
    assert(Mermaid.render(all).contains("**Blast radius: high.** It changes every module.\n"), Mermaid.render(all))
  }

  test("an added module is named but not counted, since nothing depended on it before") {
    val diff   = compare(leaves("a" -> "1", "b" -> "1", "c" -> "1"), leaves("a" -> "1", "b" -> "1", "c" -> "1", "n" -> "1"))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.changed, radius.added, radius.reached), (BlastLevel.Low, Nil, List("n"), Nil))
    assert(Mermaid.render(diff).contains("**Blast radius: low.** It adds `n`. No other module depends on what changed."), Mermaid.render(diff))
  }

  test("a module that imports code outside the modules depends on the definitions it imports, not the rest of their files") {
    def app(handle: String, retries: String = "3") = Fixtures.project(
      Fixtures
        .src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with CModule with DModule"),
      Fixtures.src("a/AModule.scala", "package app.a\nimport app.utils.http.Handlers\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule {\n  def h = app.utils.http.Handlers.handle\n}"),
      Fixtures.src("c/CModule.scala", "package app.c\nimport app.utils.http.*\ntrait CModule {\n  def s = Error.status\n}"),
      Fixtures.src("d/DModule.scala", "package app.d\nimport app.utils.json.Codecs.given\ntrait DModule"),
      Fixtures.src(
        "utils/http/Handlers.scala",
        s"package app.utils.http\nobject Handlers {\n  def handle = $handle\n}\nobject Retry {\n  def times = $retries\n}"
      ),
      Fixtures.src("utils/http/Error.scala", "package app.utils.http\nobject Error {\n  def status = 400\n}"),
      Fixtures.src("utils/json/Codecs.scala", "package app.utils.json\nobject Codecs")
    )
    val handlers = OutsideImport("utils/http", "Handlers")
    assertEquals(
      Extractor.extract(app("1")).modules.map(m => m.id -> m.outsideImports).toMap,
      Map(
        "a" -> List(handlers),
        "b" -> List(handlers),
        "c" -> List(OutsideImport("utils/http", "Error")),
        "d" -> List(OutsideImport("utils/json", "Codecs"))
      )
    )
    val diff   = compare(app("1"), app("2"))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.changed, radius.reached, radius.untraced), (BlastLevel.High, Nil, List("a", "b"), Nil))
    assert(
      Mermaid.render(diff).contains("**Blast radius: high.** No module changed. 2 of the 4 modules depend on what changed: `a`, `b`.\n"),
      Mermaid.render(diff)
    )
    val unimported = compare(app("1"), app("1", retries = "5"))
    assertEquals(
      (unimported.blastRadius.get.level, unimported.blastRadius.get.reached, unimported.blastRadius.get.untraced),
      (BlastLevel.Low, Nil, List("utils/http/Handlers.scala"))
    )
    assert(
      Mermaid
        .render(unimported)
        .contains("**Blast radius: low.** No module changed. Code that no module imports changed, which this doesn't trace.\n"),
      Mermaid.render(unimported)
    )
  }

  test("a module that imports a package's givens depends on its givens, anonymous ones too, and not on the rest of the package") {
    def codecs(encoder: String, width: String = "80") = app("a", "b", "c", "d")(
      Fixtures.src("a/services/Json.scala", "package app.a.services\nimport app.utils.json.given\nclass Json"),
      Fixtures.src("b/services/Json.scala", "package app.b.services\nimport app.utils.json.*\nclass Json"),
      Fixtures.src("utils/json/Codecs.scala", s"package app.utils.json\ngiven Encoder[Instant] = $encoder\nobject Printer {\n  def width = $width\n}")
    )
    assertEquals(compare(codecs("x"), codecs("y")).blastRadius.get.reached, List("a"))
    val printer = compare(codecs("x"), codecs("x", width = "120")).blastRadius.get
    assertEquals((printer.reached, printer.untraced), (Nil, List("utils/json/Codecs.scala")))
  }

  test("a module depends on every definition of the name it imports, when several files of the package define it") {
    def ext(int: String) = app("a", "b", "c", "d")(
      Fixtures.src("a/services/Show.scala", "package app.a.services\nimport app.utils.ext.*\nclass Show {\n  def s = 1.show\n}"),
      Fixtures.src("utils/ext/IntExt.scala", s"package app.utils.ext\nextension (x: Int) def show = $int"),
      Fixtures.src("utils/ext/StrExt.scala", "package app.utils.ext\nextension (x: String) def show = x")
    )
    assertEquals(compare(ext("x.toString"), ext("s\"#$x\"")).blastRadius.get.reached, List("a"))
  }

  test("a module depends on what the definitions it imports extend outside the modules") {
    def http(version: String, json: String = "1") = app("a", "b", "c", "d")(
      Fixtures.src("a/routers/ARouter.scala", "package app.a.routers\nimport app.utils.http.BaseRouter\nclass ARouter extends BaseRouter"),
      Fixtures.src(
        "utils/http/BaseRouter.scala",
        "package app.utils.http\nimport app.utils.json.JsonProtocol\ntrait BaseRouter extends JsonProtocol with Directives with ApiVersionDirectives"
      ),
      Fixtures.src(
        "utils/http/ApiVersionDirectives.scala",
        s"package app.utils.http\ntrait ApiVersionDirectives {\n  def version = $version\n}\nobject ApiVersionDirectives extends ApiVersionDirectives"
      ),
      Fixtures.src("utils/json/JsonProtocol.scala", s"package app.utils.json\ntrait JsonProtocol {\n  def printer = $json\n}")
    )
    val version = compare(http("1"), http("2")).blastRadius.get
    assertEquals((version.reached, version.untraced), (List("a"), Nil))
    assertEquals(compare(http("1"), http("1", json = "2")).blastRadius.get.reached, List("a"))
  }

  test("a definition moved unchanged to another file of its package reaches no module") {
    def handlers(file: String, handle: String = "1") = app("a", "b", "c", "d")(
      Fixtures.src("a/services/Http.scala", "package app.a.services\nimport app.utils.http.Handlers\nclass Http"),
      Fixtures.src(s"utils/http/$file", s"package app.utils.http\nobject Handlers {\n  def handle = $handle\n}")
    )
    val moved = compare(handlers("Handlers.scala"), handlers("Http.scala")).blastRadius.get
    assertEquals((moved.level, moved.reached, moved.untraced), (BlastLevel.Low, Nil, Nil))
    assertEquals(compare(handlers("Handlers.scala"), handlers("Http.scala", handle = "2")).blastRadius.get.reached, List("a"))
  }

  test("a module that starts using a table or subscribing to an event doesn't reach the modules already on it") {
    def shop(uses: Boolean) = app("user", "shop", "c", "d", "e")(
      Fixtures.src(
        "user/repositories/AccountTable.scala",
        "package app.user.repositories\nobject AccountTable extends Table[Account](\"account\") {\n  val id = column(\"id\", uuid)\n}"
      ),
      Fixtures.src("user/repositories/UserRepository.scala", "package app.user.repositories\nclass UserRepository {\n  def f = AccountTable\n}"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
      ),
      Fixtures.src(
        "shop/services/ShopService.scala",
        if (uses)
          "package app.shop.services\nimport app.user.domain.UserDeleted\nimport app.user.repositories.AccountTable\nclass ShopService {\n  def f = AccountTable\n  val s = OutboxSubscription[UserDeleted](\"shop\")(e => ???)\n}"
        else "package app.shop.services\nclass ShopService"
      )
    )
    val diff = compare(shop(false), shop(true))
    assertEquals(
      (diff.tables.map(_.status), diff.events.map(_.status), diff.blastRadius.get.reached),
      (List(Status.Changed), List(Status.Changed), Nil)
    )
    assertEquals(compare(shop(true), shop(false)).blastRadius.get.reached, Nil)
  }

  test("a topic or event reaches its publishers and subscribers when its type changes, not when another type in its file does") {
    def events(user: String, audit: String = "x: Int") = app("user", "shop", "c", "d", "e", "f")(
      Fixtures.src(
        "utils/events/Events.scala",
        s"package app.utils.events\nfinal case class UserDeleted($user)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}\nfinal case class UserEvent($user)\nfinal case class Audit($audit)"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
      ),
      Fixtures.src(
        "user/services/Bus.scala",
        "package app.user.services\nobject Bus {\n  lazy val bus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"users\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "shop/services/ShopService.scala",
        "package app.shop.services\nclass ShopService {\n  val s = OutboxSubscription[UserDeleted](\"shop\")(e => ???)\n}"
      ),
      Fixtures.src(
        "c/services/CService.scala",
        "package app.c.services\nclass CService(bus: EventBus[UserEvent]) {\n  def ws = bus.subscribeAwait\n}"
      )
    )
    val audit = compare(events("id: Id"), events("id: Id", audit = "x: Long")).blastRadius.get
    assertEquals((audit.level, audit.reached, audit.untraced), (BlastLevel.Low, Nil, List("utils/events/Events.scala")))
    val user = compare(events("id: Id"), events("id: Id, reason: String"))
    assertEquals(user.modules.flatMap(_.head).map(m => m.id -> m.outsideImports).filter(_._2.nonEmpty), Nil)
    assertEquals(user.blastRadius.get.reached, List("c", "shop", "user"))
  }

  test("a change no other module depends on is low, and code no module imports is mentioned but not counted") {
    def app(size: String, retries: String) = Fixtures.project(
      (modules(size, "b" -> Nil, "c" -> Nil).scalaFiles :+ Fixtures
        .src("utils/task/Scheduler.scala", s"package app.utils.task\nclass Scheduler {\n  def retries = $retries\n}"))*
    )
    val diff   = compare(app("1", "3"), app("2", "5"))
    val radius = diff.blastRadius.get
    assertEquals((radius.level, radius.reached, radius.untraced), (BlastLevel.Low, Nil, List("utils/task/Scheduler.scala")))
    assert(
      Mermaid
        .render(diff)
        .contains(
          "**Blast radius: low.** It changes `a`. No other module depends on what changed. Code that no module imports changed too, which this doesn't trace.\n"
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

  test("a function a migration creates doesn't run, so its body destroys and writes nothing, while a do block's does") {
    val sql = List(
      "create or replace function purge() returns void as $$ delete from sessions where expires_at < now(); $$ language sql;",
      "create function bump() returns trigger language plpgsql as $body$ begin update account set version = version + 1; return new; end $body$;",
      "do $$ begin update account set nick = 'x; drop table account'; end $$;"
    ).mkString("\n")
    val migrations = MigrationExtractor.extract(List(Fixtures.migration("V8__functions.sql", sql)))
    assertEquals(
      (migrations.flatMap(_.destructive), migrations.flatMap(_.writes)),
      (List(Destructive("updates rows in", "account")), List("account"))
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

  test("an edited repeatable migration is hard to undo only for what it newly destroys, since it's meant to run again") {
    def app(columns: String, cleanup: String = "") = Fixtures.projectWithMigrations(
      modules("1", "b" -> Nil, "c" -> Nil).scalaFiles,
      List(
        Fixtures.migration("V1__init.sql", "create table account (id uuid primary key, email text);"),
        Fixtures.migration("R__views.sql", s"${cleanup}create or replace view contacts as select $columns from account;")
      )
    )
    val radius = compare(app("id"), app("id, email")).blastRadius.get
    assertEquals((radius.level, radius.irreversible), (BlastLevel.Low, Nil))
    val cleanup = "delete from account where email is null;\n"
    assertEquals(
      compare(app("id"), app("id", cleanup)).blastRadius.get.irreversible,
      List(Irreversible.Migrated("R__views.sql", "deletes rows from", "account"))
    )
    assertEquals(compare(app("id", cleanup), app("id, email", cleanup)).blastRadius.get.irreversible, Nil)
    assertEquals(
      compare(app("id", cleanup), app("id", cleanup + "delete from account where id is null;\n")).blastRadius.get.irreversible,
      List(Irreversible.Migrated("R__views.sql", "deletes rows from", "account"))
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

  test("an outbox event whose type is a sealed trait changes with the subtypes declared next to it") {
    def events(created: String, rest: String = "") = app("user")(
      Fixtures.src(
        "user/domain/UserEvent.scala",
        s"package app.user.domain\nsealed trait UserEvent\nobject UserEvent {\n  given DomainEventDescriptor[UserEvent] = DomainEventDescriptor(\"user-event.v1\", \"user\", _.id)\n}\nsealed trait Lifecycle extends UserEvent\nfinal case class Created($created) extends Lifecycle\ncase object Deleted extends UserEvent\n$rest"
      )
    )
    val payload = List(Irreversible.Payload("user-event.v1"))
    assertEquals(compare(events("id: Id"), events("id: Id, name: String")).blastRadius.get.irreversible, payload)
    assertEquals(
      compare(
        events("id: Id"),
        events("id: Id", rest = "object Created {\n  given Encoder[Created] = deriveEncoder\n}")
      ).blastRadius.get.irreversible,
      payload
    )
    assertEquals(compare(events("id: Id"), events("id: Id", rest = "class Helper")).blastRadius.get.irreversible, Nil)
  }

  test("the full map has no blast radius") {
    val full = ArchDiff.full(Extractor.extract(modules("1", "b" -> List("a"))), "working tree")
    assertEquals(full.blastRadius, None)
    assert(!Html.render(full).contains("Blast radius"))
  }
}
