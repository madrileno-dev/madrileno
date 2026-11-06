package workspace

class AgentsMdSuite extends munit.FunSuite {
  private val members =
    List(Member("api", "backend", "Scala 3 API", "* rules: `{dir}/AGENTS.md`"), Member("web", "frontend", "React app", "* sync: `../{backend}/x`"))
  private val block = AgentsMd.block(members, backendDir = "api")

  test("the block is fenced by the markers") {
    assert(block.startsWith(AgentsMd.Begin))
    assert(block.endsWith(AgentsMd.End))
  }

  test("lists every member in a table") {
    assert(block.contains("| `api/` | backend | Scala 3 API |"))
    assert(block.contains("| `web/` | frontend | React app |"))
  }

  test("gives each member a section with its directories filled in") {
    assert(block.contains("## api (backend)\n\n* rules: `api/AGENTS.md`"))
    assert(block.contains("## web (frontend)\n\n* sync: `../api/x`"))
  }

  test("tells agents to read each member's AGENTS.md") {
    assert(block.contains("Read it before working there"))
  }

  test("a new file is the block alone") {
    assertEquals(AgentsMd.merge(None, block), block + "\n")
  }

  test("an existing block is replaced and the text around it kept") {
    val existing = s"# Our monorepo\n\n${AgentsMd.Begin}\nold\n${AgentsMd.End}\n\nOur own rules\n"
    assertEquals(AgentsMd.merge(Some(existing), block), s"# Our monorepo\n\n$block\n\nOur own rules\n")
  }

  test("a file without the block gets it appended") {
    assertEquals(AgentsMd.merge(Some("# Ours\n"), block), s"# Ours\n\n$block\n")
  }
}
