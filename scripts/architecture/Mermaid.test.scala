package architecture

class MermaidSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")
  private val out  = Mermaid.render(diff)

  test("header, flowchart and class definitions") {
    assert(out.startsWith("### Architecture changes: `main@abc1234` → `working tree`\n\n```mermaid\nflowchart LR\n"), out)
    assert(out.contains("classDef added fill:#2da44e,stroke:#1a7f37,color:#ffffff"))
  }

  test("added module is a subgraph with its added component, removed module is dashed, user is a neighbour") {
    assert(out.contains("subgraph module_order[\"#43; order\"]\n    direction LR\n"), out)
    assert(out.contains("component_order_OrderService[\"#43; OrderService (service)\"]"), out)
    assert(out.contains("module_shop[\"− shop\"]"), out)
    assert(out.contains("module_user[\"user\"]"), out)
    assert(out.contains("module_shop -.->|userRepository| module_user"), out)
    assert(out.contains("module_order -->|userRepository| module_user"), out)
  }

  test("labels are escaped") {
    val node = GNode("table:\"x<y>\"", "\"x<y>\"", NodeKind.Table, None, Status.Added)
    assertEquals(Mermaid.nodeLine(node), "table__x_y__[(\"#43; #quot;x#lt;y#gt;#quot;\")]")
  }

  test("no label starts with a Markdown list marker, which Mermaid 11 renders as an error") {
    assert(!"""\["\+ """.r.findFirstIn(out).isDefined, out)
  }

  test("an unchanged diff says so and has no code block") {
    val same = ArchDiff.compare(Fixtures.diffHead, Fixtures.diffHead, "main@abc1234", "working tree")
    assertEquals(Mermaid.render(same), "No architecture changes between `main@abc1234` and `working tree`.\n")
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
}
