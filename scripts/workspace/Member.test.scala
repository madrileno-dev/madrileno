package workspace

class MemberSuite extends munit.FunSuite {
  private val text =
    """---
      |role: backend
      |summary: Scala 3 API, port 9000
      |---
      |* start: `(cd {dir} && ./scripts/app.scala start)`
      |""".stripMargin

  test("reads role, summary and body") {
    assertEquals(
      Member.parse("api", text),
      Right(Member("api", "backend", "Scala 3 API, port 9000", "* start: `(cd {dir} && ./scripts/app.scala start)`"))
    )
  }

  test("needs the frontmatter") {
    assert(Member.parse("api", "* start").left.exists(_.contains("frontmatter")))
  }

  test("needs a role and a summary") {
    assert(Member.parse("api", "---\nrole: backend\n---\nbody").left.exists(_.contains("summary")))
    assert(Member.parse("api", "---\nsummary: x\n---\nbody").left.exists(_.contains("role")))
  }

  test("a role is one lower-case word") {
    assert(Member.parse("api", "---\nrole: Back End\nsummary: x\n---\n").left.exists(_.contains("role")))
  }

  test("fills in its own directory and the backend's") {
    val member = Member("web", "frontend", "x", "`(cd {dir} && pnpm run sync-contracts ../{backend}/target)`")
    assertEquals(member.section(backendDir = "api"), "`(cd web && pnpm run sync-contracts ../api/target)`")
  }
}
