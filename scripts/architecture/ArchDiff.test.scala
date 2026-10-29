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
}
