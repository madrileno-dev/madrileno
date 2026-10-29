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

## Reviewing what the assistant built

An assistant can hand over a large change faster than anyone can read it. Start the review from the architecture rather than the line diff. `./scripts/architecture.scala --diff main` writes an HTML map of what the branch changed:
- new modules, services and repositories
- new dependencies between modules
- events and tables

It also warns where the change drifts from the template's conventions. `AGENTS.md` tells the assistant to paste the same diff, as Mermaid, into the PR description (`--diff main --mermaid`), so it's already there when you open the PR. See [scripts.md](scripts.md#architecturescala).

## How the assistant runs the app

The assistant never runs a watch loop, `sbt run`, or a bare `sbt` shell: all of them block its shell until something kills them. It uses the one-shot tasks from [dev-workflow.md](dev-workflow.md#running-the-app-without-a-watch-loop) instead, under two rules:

- **Check before starting.** It runs `curl -sf http://localhost:9000/v1/health-check` first. If the app answers, that is usually your `~reStart` loop, which rebuilds on save; the assistant uses that app and does not run `reStart`, because only one of them can own port 9000.
- **Own what it starts.** If nothing answers, it starts the app with `sbt --client reStart`, waits for the health check for up to 60 seconds, reads the end of `logs/madrileno.log` if the app never answers, and reruns `reStart` after its own code changes. It only stops an app it started itself.

`AGENTS.md` states the same rules, so the assistant follows them without being told.
