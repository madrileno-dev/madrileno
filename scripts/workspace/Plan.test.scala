package workspace

class PlanSuite extends munit.FunSuite {
  private def member(
    root: os.Path,
    dir: String,
    role: String,
    skills: String*
  ): Unit = {
    os.write(
      root / dir / ".agents" / "workspace.md",
      s"---\nrole: $role\nsummary: the $role\n---\n* rules: `{dir}/AGENTS.md`\n",
      createFolders = true
    )
    skills.foreach(s => os.write(root / dir / ".agents" / "skills" / s / "SKILL.md", s"---\nname: $s\n---\n", createFolders = true))
  }

  private def workspace(): os.Path = {
    val root = os.temp.dir()
    member(root, "api", "backend", "backend-add-module")
    member(root, "web", "frontend", "frontend-use-api")
    os.write(root / "api" / ".mcp.json", """{"mcpServers": {"metals": {"type": "http", "url": "http://localhost:8911/mcp"}}}""")
    os.makeDir.all(root / "notes")
    root
  }

  private def generate(root: os.Path): Unit = Plan.apply(root, Plan.changes(root, "api").toOption.get)

  test("a fresh root gets every file and link") {
    val root = workspace()
    generate(root)
    assert(os.read(root / "AGENTS.md").contains("## web (frontend)"))
    assertEquals(os.read(root / "CLAUDE.md"), "@AGENTS.md\n")
    assertEquals(os.readLink(root / ".agents" / "skills" / "backend-add-module").toString, "../../api/.agents/skills/backend-add-module")
    assert(os.isFile(root / ".agents" / "skills" / "frontend-use-api" / "SKILL.md"))
    assertEquals(os.readLink(root / ".claude" / "skills").toString, "../.agents/skills")
    assert(os.read(root / ".mcp.json").contains("8911"))
    assert(os.read(root / ".codex" / "config.toml").contains("[mcp_servers.metals]"))
  }

  test("a generated root has nothing left to change") {
    val root = workspace()
    generate(root)
    assertEquals(Plan.changes(root, "api"), Right(Nil))
  }

  test("an edited member section makes the root stale") {
    val root = workspace()
    generate(root)
    os.write.over(root / "web" / ".agents" / "workspace.md", "---\nrole: frontend\nsummary: changed\n---\n")
    assertEquals(Plan.changes(root, "api").map(_.map(_.path)), Right(List(os.rel / "AGENTS.md")))
  }

  test("a directory without .agents/workspace.md is not a member") {
    val root = workspace()
    generate(root)
    assert(!os.read(root / "AGENTS.md").contains("notes"))
  }

  test("a link to a skill that is gone is removed, other entries are left alone") {
    val root = workspace()
    generate(root)
    os.remove.all(root / "web" / ".agents" / "skills" / "frontend-use-api")
    os.write(root / ".agents" / "skills" / "ours" / "SKILL.md", "x", createFolders = true)
    generate(root)
    assert(!os.exists(root / ".agents" / "skills" / "frontend-use-api", followLinks = false))
    assert(os.isFile(root / ".agents" / "skills" / "ours" / "SKILL.md"))
  }

  test("an existing CLAUDE.md gets the import once") {
    val root = workspace()
    os.write(root / "CLAUDE.md", "# Ours\n")
    generate(root)
    generate(root)
    assertEquals(os.read(root / "CLAUDE.md"), "# Ours\n\n@AGENTS.md\n")
  }

  test("the backend must be a member of the root") {
    assert(Plan.changes(workspace(), "elsewhere").left.exists(_.contains("elsewhere")))
  }

  test("two members with a skill of the same name clash") {
    val root = workspace()
    member(root, "app", "mobile", "frontend-use-api")
    assert(Plan.changes(root, "api").left.exists(_.contains("frontend-use-api")))
  }

  test("a real .claude/skills directory is not replaced") {
    val root = workspace()
    os.makeDir.all(root / ".claude" / "skills")
    assert(Plan.changes(root, "api").left.exists(_.contains(".claude/skills")))
  }

  test("a skill directory in the way of a link is not replaced") {
    val root = workspace()
    os.makeDir.all(root / ".agents" / "skills" / "backend-add-module")
    assert(Plan.changes(root, "api").left.exists(_.contains("backend-add-module")))
  }
}
