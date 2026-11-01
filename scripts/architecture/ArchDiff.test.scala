package architecture

class ArchDiffSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")

  test("module statuses ignore line counts and method bodies") {
    assertEquals(diff.modules.map(m => m.id -> m.status), List("order" -> Status.Added, "shop" -> Status.Removed, "user" -> Status.Unchanged))
    assertEquals(diff.modules.find(_.id == "order").get.components.map(c => c.name -> c.status), List("OrderService" -> Status.Added))
  }

  test("a module whose own members are identical but whose references changed is Changed") {
    def model(symbol: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("shop/ShopModule.scala", s"package app.shop\nimport app.user.domain.$symbol\ntrait ShopModule")
      )
    )
    val changed = ArchDiff.compare(model("Email"), model("Phone"), "b", "h")
    assertEquals(changed.modules.map(m => m.id -> m.status), List("shop" -> Status.Changed, "user" -> Status.Unchanged))
  }

  test("an unclassified item that only moved lines stays Existing") {
    def model(padding: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src(
          "user/services/S.scala",
          s"package app.user.services\n${padding}class S(outbox: Outbox) {\n  def p(ev: E) = outbox.publishTransactionally(ev)\n}"
        )
      )
    )
    val moved = ArchDiff.compare(model(""), model("\n\n"), "b", "h")
    assertEquals(moved.unclassified.map(u => u.item.key -> u.status), List("outbox-publish:user/services/S.scala:S:ev" -> WarningStatus.Existing))
  }

  test("a cycle that shrinks stays existing, and one that grows is new without the old one resolved") {
    def model(imports: (String, String)*) = Extractor.extract(Fixtures.project(List("a", "b", "c", "d").map { id =>
      val lines = imports.collect { case (`id`, to) => s"import app.$to.domain.${to.toUpperCase}\n" }.mkString
      Fixtures.src(s"$id/${id.toUpperCase}Module.scala", s"package app.$id\n${lines}trait ${id.toUpperCase}Module")
    }*))
    val base   = model("a" -> "b", "b" -> "a", "b" -> "c", "c" -> "a")
    val shrunk = ArchDiff.compare(base, model("a" -> "b", "b" -> "a", "b" -> "c"), "b", "h")
    assertEquals(
      shrunk.warnings.filter(_.warning.kind == WarningKind.ModuleCycle).map(w => w.warning.key -> w.status),
      List("cycle:a,b" -> WarningStatus.Existing, "cycle:a,b,c" -> WarningStatus.Resolved)
    )
    val grown = ArchDiff.compare(base, model("a" -> "b", "b" -> "a", "b" -> "c", "c" -> "a", "c" -> "d", "d" -> "a"), "b", "h")
    assertEquals(
      grown.warnings.filter(_.warning.kind == WarningKind.ModuleCycle).map(w => w.warning.key -> w.status),
      List("cycle:a,b,c,d" -> WarningStatus.New)
    )
  }

  test("members of a module carry their own status") {
    def model(members: String) =
      Extractor.extract(Fixtures.project(Fixtures.src("shop/ShopModule.scala", s"package app.shop\ntrait ShopModule {\n$members\n}")))
    val diff =
      ArchDiff.compare(model("  val a: A\n  val b: B\n  lazy val x: X = ???"), model("  val b: B\n  val c: C\n  lazy val x: X = ???"), "b", "h")
    assertEquals(
      diff.modules.head.members.map(m => (m.section, m.text, m.status)),
      List(
        (MemberSection.Consumes, "a: A", Status.Removed),
        (MemberSection.Consumes, "b: B", Status.Unchanged),
        (MemberSection.Consumes, "c: C", Status.Added),
        (MemberSection.Provides, "x: X", Status.Unchanged)
      )
    )
  }

  test("edges carry their own status") {
    assertEquals(
      diff.edges.filter(_.status != Status.Unchanged).map(e => (e.edge.from, e.edge.to, e.status)).toSet,
      Set(("module:order", "module:user", Status.Added), ("module:shop", "module:user", Status.Removed))
    )
  }

  test("full mode marks everything unchanged and every warning existing") {
    val full = ArchDiff.full(Fixtures.diffHead, "working tree")
    assert(!full.isDiff)
    assert(full.modules.forall(_.status == Status.Unchanged))
    assert(full.warnings.forall(_.status == WarningStatus.Existing))
  }

  test("a provided member without a visible type is listed by its name alone") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src(
          "user/UserModule.scala",
          "package app.user\ntrait UserModule {\n  lazy val nightly = Task.recurring(\"nightly\", Schedule.RecurringWithFixedRate(1.day))(_ => IO.unit)\n}"
        )
      )
    )
    assertEquals(ArchDiff.full(model, "h").modules.flatMap(_.members.map(_.text)), List("nightly"))
  }
}
