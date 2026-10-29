package architecture

class MetricsSuite extends munit.FunSuite {
  private val model = Extractor.extract(
    Fixtures.project(
      Fixtures
        .src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with CModule with DModule"),
      Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\nimport app.c.domain.Y\ntrait BModule"),
      Fixtures.src("c/CModule.scala", "package app.c\nimport app.b.domain.Z\nimport app.b.services.Helper\ntrait CModule"),
      Fixtures.src("d/DModule.scala", "package app.d\ntrait DModule")
    )
  )
  private val metrics = ArchMetrics.of(model)

  test("propagation cost is the share of other modules a change can reach") {
    assertEqualsDouble(metrics.propagationCost, 4.0 / 12.0, 1e-9)
  }

  test("largest cycle, stability violations, bypasses and drift") {
    assertEquals(metrics.largestCycle, 2)
    assertEquals(metrics.stabilityViolations, 1)
    assertEquals(metrics.cakeBypasses, 1)
    assertEquals(metrics.drift, model.warnings.size + model.unclassified.size)
  }

  test("coupling per module: dependents, dependencies and instability") {
    assertEquals(
      ArchMetrics
        .coupling(model)
        .map(c => (c.id, c.dependents, c.dependencies, BigDecimal(c.instability).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble)),
      List(("a", 0, 1, 1.0), ("b", 2, 1, 0.33), ("c", 1, 1, 0.5), ("d", 0, 0, 0.0))
    )
  }

  test("a project without modules has zero metrics") {
    assertEquals(ArchMetrics.of(ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)), ArchMetrics(0.0, 0, 0, 0, 0))
  }

  test("percentages show one decimal when needed, and a trend needs a visible move") {
    val row  = ArchMetrics.rows.find(_.name == "Propagation cost").get
    val base = ArchMetrics(0.2649, 0, 0, 0, 0)
    assertEquals(row.format(0.2649), "26.5%")
    assertEquals(row.format(0.2), "20%")
    assertEquals(ArchMetrics.trend(row, base, base.copy(propagationCost = 0.2651)), None)
    assertEquals(ArchMetrics.trend(row, base, base.copy(propagationCost = 0.2675)), Some(true))
  }
}
