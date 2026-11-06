# AI-assisted development

Driving this codebase with an AI assistant (Claude Code or Codex) works best with two MCP servers running. They're complementary:

| Server                          | Gives the assistant                                                                 | Scope                  |
| ------------------------------- | ----------------------------------------------------------------------------------- | ---------------------- |
| **Metals** (`metals-mcp`)       | Live build tools on *your* code: compile, run tests, import-build, find-dep, inspect | Read-write, your repo  |
| **madrileno** (`mcp-server.scala`) | Read-only *upstream* reference at a pinned commit: canonical modules, docs, diffs   | Read-only, the template |

Both are optional — the project builds and runs without them. They earn their keep when you're pairing with Claude: Metals gives it a real compile/test loop instead of guessing, and madrileno lets it copy non-obvious patterns from the reference modules (see [mcp.md](mcp.md)).

Both listen on `localhost` and are wired in the project-scoped `.mcp.json` (Claude Code) and `.codex/config.toml` (Codex) at the repo root, which are [committed by design](https://code.claude.com/docs/en/mcp#project-scope) — every clone gets the wiring for free.

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

## How Codex connects

Codex reads the same two servers from the committed `.codex/config.toml`, but only in a project you have trusted: accept the prompt on the first interactive run, or add the project to your own `~/.codex/config.toml`:

```toml
[projects."/path/to/your/project"]
trust_level = "trusted"
```

`codex mcp list`, run from the project root, shows both servers once the project is trusted. Codex reads `AGENTS.md` itself; it has no `CLAUDE.md` equivalent to set up.

## Skills

`AGENTS.md` holds what applies to every task. Procedures that only some tasks need are skills under `.agents/skills/<name>/SKILL.md`, which the assistant loads when a task matches the skill's description:

| Skill | For |
| --- | --- |
| `backend-add-module` | a new aggregate with its own table and endpoints, starting from `scaffold-module` |
| `backend-schema-change` | a migration and the `Row`, `Table` and repository code that read it |
| `backend-add-endpoint` | a route, its DTOs and error codes, and the router spec behind the contract |

Codex reads `.agents/skills/`; Claude Code reads `.claude/skills/`, which is a symlink to it, so there is one copy. On Windows, a checkout without symlink support (`core.symlinks=false`) turns that link into a text file and Claude Code finds no skills: enable symlinks in Git for Windows, or copy `.agents/skills` to `.claude/skills` by hand.

A skill works without the docs, which `init-project` deletes by default: it carries the steps, the rules that are easy to get wrong and the check that ends it, and names a doc for depth, read through `madrileno_doc` (see [mcp.md](mcp.md)). Add one when assistants keep rebuilding the same procedure from existing code. `AgentInstructionsSpec` fails when a skill's name doesn't match its folder or lacks the `backend-` prefix, when it names a doc that doesn't exist, when `AGENTS.md` and `.agents/skills/` list different skills, or when the `.claude/skills` link is missing.

## Working from a parent directory

Started in a directory that holds this backend and its apps, an assistant finds none of the above: instructions, skills and MCP config are read from where it starts, not from below. `./scripts/workspace.scala generate` writes them at that parent; see [workspace.md](workspace.md).

## Reviewing what the assistant built

An assistant can hand over a large change faster than anyone can read it. Start the review from the architecture rather than the line diff. `./scripts/architecture.scala --diff main` writes an HTML map of what the branch changed:
- new modules, services and repositories
- new dependencies between modules
- events and tables

It starts with the blast radius: how many modules changed, how many others depend on what changed, and whether anything is hard to undo, such as a dropped column or an edited migration. A low one may need only a skim, a high one a close read. It also warns where the change drifts from the template's conventions. `AGENTS.md` tells the assistant to run the same check, as Mermaid (`--diff main --mermaid`), after changes that add or move modules, services, repositories, events or tables, and to fix the new warnings and unclassified items it lists. It also tells the assistant to paste that output into the PR description, so the picture is already there when you open the PR. See [scripts.md](scripts.md#architecturescala).

## How the assistant runs the app

The assistant never runs a watch loop, `sbt run`, or a bare `sbt` shell: all of them block its shell until something kills them. It drives the app through `./scripts/app.scala` (see [scripts.md](scripts.md#appscala)), which owns the process, the health check and the log tail so that the assistant does not have to:

- `./scripts/app.scala start` compiles through the sbt client, applies pending migrations, runs the app in its own process and returns once `/v1/health-check` answers. When it fails, the message carries the last log lines, and no half-started app is left behind.
- `./scripts/app.scala restart` after a code change or a `.env` change; `stop`, `status` and `logs` do what they say.
- When `start` reports an app it did not start, that is usually your `~reStart` loop, which rebuilds on save. The assistant uses that app and leaves it alone, because only one of them can own the port.

`AGENTS.md` states the same rules, so the assistant follows them without being told.
