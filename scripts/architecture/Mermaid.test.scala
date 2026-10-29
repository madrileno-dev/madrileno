package architecture

class MermaidSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")
  private val out  = Mermaid.render(diff)

  test("header, flowchart and class definitions") {
    assert(out.startsWith("### Architecture changes: `main@abc1234` → `working tree`\n\n```mermaid\n---\nconfig:\n  theme: base\n"), out)
    assert(out.contains("---\nflowchart LR\n"), out)
    assert(out.contains("classDef added fill:#2f7d4a,stroke:#1f5c35,color:#ffffff"))
  }

  test("added module is a subgraph with its added component, removed module is dashed, user is a neighbour") {
    assert(out.contains("subgraph module_order[\"#43;#160;order\"]\n    direction LR\n"), out)
    assert(out.contains("component_order_OrderService[\"#43;#160;OrderService (service)\"]"), out)
    assert(out.contains("module_shop[\"−#160;shop\"]"), out)
    assert(out.contains("module_user[\"user\"]"), out)
    assert(out.contains("module_shop -.->|\"wires userRepository\"| module_user"), out)
    assert(out.contains("module_order -->|\"wires userRepository\"| module_user"), out)
  }

  test("labels are escaped") {
    val node = GNode("table:\"x<y>\"", "\"x<y>\"", NodeKind.Table, None, Status.Added)
    assertEquals(Mermaid.nodeLine(node), "table__x_y__[(\"#43;#160;#quot;x#lt;y#gt;#quot;\")]")
  }

  test("no label starts with a Markdown list marker, which Mermaid 11 renders as an error") {
    assert(!"""\["\+ """.r.findFirstIn(out).isDefined, out)
  }

  test("an unchanged diff says so and has no code block") {
    val same = ArchDiff.compare(Fixtures.diffHead, Fixtures.diffHead, "main@abc1234", "working tree")
    assertEquals(
      Mermaid.render(same),
      "No architecture changes between `main@abc1234` and `working tree`.\n\n<sub>Mapped by Madrileño's <code>scripts/architecture.scala</code></sub>\n"
    )
  }

  test("more than 30 boxes collapse components") {
    val many = (1 to 31).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module {\n  private val s = wire[S$i]\n}")) ++
      (1 to 31).map(i => Fixtures.src(s"m$i/services/S$i.scala", s"package app.m$i.services\nclass S$i"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val graph = GraphView.changes(ArchDiff.compare(empty, Extractor.extract(Fixtures.project(many*)), "b", "h"))
    assert(graph.collapsed)
    assert(graph.nodes.forall(_.kind != NodeKind.Component))
  }

  test("a diagram too large for GitHub becomes a textual summary") {
    val many  = (1 to 90).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val text  = Mermaid.render(ArchDiff.compare(empty, Extractor.extract(Fixtures.project(many*)), "b", "h"))
    assert(!text.contains("```mermaid"), text.take(300))
    assert(text.contains("**Added modules (90):** m1, m10, m11"), text.take(600))
  }

  test("edges between two unchanged neighbours are left out") {
    def model(extra: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with HubModule"),
        Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule"),
        Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
        Fixtures.src("hub/HubModule.scala", s"package app.hub\nimport app.a.domain.Y\nimport app.b.domain.Z\ntrait HubModule {\n$extra\n}")
      )
    )
    val graph = GraphView.changes(ArchDiff.compare(model(""), model("  lazy val extra: Extra = ???"), "b", "h"))
    assertEquals(graph.nodes.map(_.key).toSet, Set("module:hub", "module:a", "module:b"))
    assertEquals(graph.edges.map(e => e.from -> e.to).toSet, Set("module:hub" -> "module:a", "module:hub" -> "module:b"))
  }

  test("every status marker is glued to its label with a non-breaking space") {
    assert(Mermaid.nodeLine(GNode("module:a", "a", NodeKind.Module, None, Status.Changed)).contains("~#160;a"))
    assert(Mermaid.nodeLine(GNode("module:a", "a", NodeKind.Module, None, Status.Removed)).contains("−#160;a"))
  }

  test("edge labels name what kind of dependency they carry") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.user.domain.UserId\nimport app.user.services.Users\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}"
        )
      )
    )
    val labels = GraphView.modules(ArchDiff.full(model, "h")).edges.map(_.label)
    assertEquals(labels, List("wires userRepository · imports domain · uses Users"))
  }

  test("labels name unwired internal types, leave out wired ones and cap the list") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
        Fixtures.src("user/repositories/UserRepository.scala", "package app.user.repositories\nclass UserRepository"),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.user.repositories.UserRepository\nimport app.user.services.{A, B, C, D, E}\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}"
        )
      )
    )
    assertEquals(GraphView.modules(ArchDiff.full(model, "h")).edges.map(_.label), List("wires userRepository · uses A, B, C, +2 more"))
  }

  test("the diagram uses the Madrileño palette and credits it") {
    assert(out.contains("    primaryTextColor: \"#1e1e24\""), out)
    assert(out.contains("    clusterBkg: \"#f5f1ea\""), out)
    assert(!out.contains("fontFamily"), "a font GitHub lacks makes Mermaid measure and render with different fonts, clipping labels")
    assert(out.contains("<sub>Mapped by Madrileño's <code>scripts/architecture.scala</code></sub>"), out)
  }

  private def pair(cyclic: Boolean) = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
      Fixtures.src("a/AModule.scala", if (cyclic) "package app.a\nimport app.b.domain.X\ntrait AModule" else "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", if (cyclic) "package app.b\nimport app.a.domain.Y\ntrait BModule" else "package app.b\ntrait BModule")
    )
  )

  test("a metrics table compares base and branch, lower is better") {
    val text = Mermaid.render(ArchDiff.compare(pair(cyclic = false), pair(cyclic = true), "main@abc1234", "working tree"))
    assert(text.contains("**Architecture metrics** (lower is better)"), text)
    assert(text.contains("| Metric | `main@abc1234` | `working tree` | |"), text)
    assert(text.contains("| Propagation cost | 0% | 100% | ▲ worse |"), text)
    assert(text.contains("| Largest cycle | 0 | 2 | ▲ worse |"), text)
    assert(text.contains("| Cake bypasses | 0 | 0 | same |"), text)
    assert(text.indexOf("**Architecture metrics**") < text.indexOf("**New warnings**"), text)
  }

  test("metrics that did not move collapse to one line") {
    val text = Mermaid.render(ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "b", "h"))
    assert(
      text.contains(
        "**Architecture metrics** unchanged: propagation cost 50%, largest cycle 0, stable-dependency violations 0, cake bypasses 0, drift 0."
      ),
      text
    )
  }

  test("edge labels are quoted so consumer names with brackets cannot break the diagram") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src(
          "user/domain/Gone.scala",
          "package app.user.domain\nobject Gone {\n  given DomainEventDescriptor[Gone] = DomainEventDescriptor(\"gone.v1\", \"user\", _.id)\n}"
        ),
        Fixtures.src(
          "user/services/S.scala",
          "package app.user.services\nclass S(outbox: Outbox) {\n  def p(id: Id) = outbox.publishTransactionally(Gone(id))\n}"
        ),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\ntrait ShopModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Gone](\"shop (cleanup) [v2]\")(h)\n}"
        )
      )
    )
    val text = Mermaid.render(ArchDiff.compare(ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil), model, "b", "h"))
    assert(text.contains("""-->|"as shop (cleanup) [v2]"| module_shop"""), text)
  }

  test("a given import names its package in the label") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\nimport app.user.services.given\ntrait ShopModule")
      )
    )
    assertEquals(GraphView.modules(ArchDiff.full(model, "h")).edges.map(_.label), List("uses services.given"))
  }
}
