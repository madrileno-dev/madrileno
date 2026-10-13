# AI-assisted development

Driving this codebase with an AI assistant (Claude Code) works best with two MCP servers running. They're complementary:

| Server                          | Gives the assistant                                                                 | Scope                  |
| ------------------------------- | ----------------------------------------------------------------------------------- | ---------------------- |
| **Metals** (`metals-mcp`)       | Live build tools on *your* code: compile, run tests, import-build, find-dep, inspect | Read-write, your repo  |
| **madrileno** (`mcp-server.scala`) | Read-only *upstream* reference at a pinned commit: canonical modules, docs, diffs   | Read-only, the template |

Both are optional — the project builds and runs without them. They earn their keep when you're pairing with Claude: Metals gives it a real compile/test loop instead of guessing, and madrileno lets it copy non-obvious patterns from the reference modules (see [mcp.md](mcp.md)).

Both listen on `localhost` and are wired in the project-scoped `.mcp.json` at the repo root, which is [committed by design](https://code.claude.com/docs/en/mcp#project-scope) — every clone gets the wiring for free.

## Why Metals matters here

[`AGENTS.md`](../AGENTS.md), the instruction file most coding agents read, tells the assistant to compile and test through tools rather than shelling out, and to use `import-build` after editing `build.sbt`, `find-dep` to look up dependencies, and `inspect` to read a class's API. **Those tools come from the Metals MCP server.** Without it, the assistant can't honor those instructions — so if you're following `AGENTS.md`, start Metals first. Claude Code reads the same file through `CLAUDE.md`, which holds a single `@AGENTS.md` import.

## Start the servers

Each is a long-lived process. Run them in their own terminals, or background them with `&`.

**Metals** — start from the repo root so it picks up this workspace:

```bash
scala-cli run --dep org.scalameta:metals-mcp_2.13:1.6.7 -- --workspace "$PWD" --port 8911
```

First launch imports the build via Bloop (runs `sbt bloopInstall`) and builds a Metals index under `.metals/` — both are gitignored caches, regenerated on demand. The server then serves MCP over HTTP on the port you pinned.

**madrileno** — see [mcp.md](mcp.md) for what it serves and how the upstream pin works:

```bash
./scripts/mcp-server.scala
```

It listens on `http://localhost:8910/mcp`.

## How Claude Code connects

The committed `.mcp.json` already points at both:

```json
{
  "mcpServers": {
    "metals":    { "type": "http", "url": "http://localhost:8911/mcp" },
    "madrileno": { "type": "http", "url": "http://localhost:8910/mcp" }
  }
}
```

Claude Code reads it automatically and prompts once to approve project-scoped servers (run `claude mcp reset-project-choices` to redo the prompt). Start the servers, then ask Claude *"compile the project"* (Metals) or *"call `madrileno_overview`"* (madrileno) to confirm both are live.
