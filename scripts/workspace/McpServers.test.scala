package workspace

class McpServersSuite extends munit.FunSuite {
  private val memberJson =
    """{"mcpServers": {"metals": {"type": "http", "url": "http://localhost:8911/mcp"},
      |  "item-library": {"type": "http", "url": "http://localhost:8910/mcp"}}}""".stripMargin
  private val servers = McpServers.read("api", memberJson).toOption.get

  test("reads a member's servers in order") {
    assertEquals(servers.map(_.name), List("metals", "item-library"))
  }

  test("a member without servers is fine") {
    assertEquals(McpServers.read("web", """{"mcpServers": {}}"""), Right(Nil))
    assertEquals(McpServers.read("web", "{}"), Right(Nil))
  }

  test("a broken .mcp.json names the member") {
    assert(McpServers.read("api", "{").left.exists(_.contains("api/.mcp.json")))
  }

  test("two members with different servers under one name clash") {
    val other = McpServers.read("web", """{"mcpServers": {"metals": {"type": "http", "url": "http://localhost:1/mcp"}}}""").toOption.get
    assert(McpServers.combine(List(servers, other)).left.exists(_.contains("metals")))
    assertEquals(McpServers.combine(List(servers, servers)).map(_.size), Right(2))
  }

  test("a new .mcp.json holds the servers") {
    val written = ujson.read(McpServers.mergeJson(None, servers).toOption.get)
    assertEquals(written("mcpServers")("metals")("url").str, "http://localhost:8911/mcp")
    assertEquals(written("mcpServers").obj.keys.toList, List("metals", "item-library"))
  }

  test("an existing .mcp.json keeps its other servers and keys") {
    val existing = """{"mcpServers": {"ours": {"command": "x"}, "metals": {"url": "old"}}, "other": 1}"""
    val written  = ujson.read(McpServers.mergeJson(Some(existing), servers).toOption.get)
    assertEquals(written("mcpServers")("ours")("command").str, "x")
    assertEquals(written("mcpServers")("metals")("url").str, "http://localhost:8911/mcp")
    assertEquals(written("other").num, 1.0)
  }

  test("a new Codex config holds one table per server") {
    assertEquals(
      McpServers.mergeToml(None, servers),
      """[mcp_servers.metals]
        |url = "http://localhost:8911/mcp"
        |
        |[mcp_servers.item-library]
        |url = "http://localhost:8910/mcp"
        |""".stripMargin
    )
  }

  test("a command server keeps its command and arguments") {
    val local = McpServers.read("api", """{"mcpServers": {"tool": {"command": "npx", "args": ["-y", "tool"]}}}""").toOption.get
    assertEquals(McpServers.mergeToml(None, local), "[mcp_servers.tool]\ncommand = \"npx\"\nargs = [\"-y\", \"tool\"]\n")
  }

  test("an existing Codex config keeps its other tables and replaces the managed ones in place") {
    val existing =
      """model = "o3"
        |
        |[mcp_servers.metals]
        |url = "old"
        |
        |[mcp_servers.ours]
        |command = "x"
        |""".stripMargin
    assertEquals(
      McpServers.mergeToml(Some(existing), servers),
      """model = "o3"
        |
        |[mcp_servers.metals]
        |url = "http://localhost:8911/mcp"
        |
        |[mcp_servers.ours]
        |command = "x"
        |
        |[mcp_servers.item-library]
        |url = "http://localhost:8910/mcp"
        |""".stripMargin
    )
  }
  test("a server name that is not a bare TOML key is quoted") {
    val dotted = McpServers.read("api", """{"mcpServers": {"my.tool": {"url": "http://localhost:1/mcp"}}}""").toOption.get
    assertEquals(McpServers.mergeToml(None, dotted), "[mcp_servers.\"my.tool\"]\nurl = \"http://localhost:1/mcp\"\n")
  }
}
