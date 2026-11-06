# Working from a parent directory

Many projects keep the backend, the web app and the mobile app side by side and start the coding assistant in the directory above them. From there it sees none of what each repo sets up for it: Claude Code and Codex read `AGENTS.md`, skills and MCP config from the directory they start in and its parents, not from the directories below. `scripts/workspace.scala` writes that layer at the parent, from a short description each repo keeps of itself.

```
my-product/              the workspace root: start the assistant here
  item-library/          this backend
  item-library-web/      the web app
  item-library-mobile/   the mobile app
```

The directory names are yours, any of the apps can be missing, and the root can be a plain directory or a monorepo with its own git history.

## Commands

Run them from the backend root:

```bash
./scripts/workspace.scala generate                # the root is the parent directory
./scripts/workspace.scala generate --root ../..   # or another one
./scripts/workspace.scala check                   # exit 1 when the root is out of date
```

`generate` prints what it wrote, or that the root is up to date. Run it again after adding or removing an app or a skill, or after editing a `.agents/workspace.md`. `check` changes nothing; it lists what is out of date and the command that fixes it. Both exit 2 when they are not run from a backend root, or when the root has no member for this backend. `doctor.scala` runs the same check when the parent directory holds a generated `AGENTS.md`.

## What it writes at the root

| Path | Content | What it leaves alone |
| --- | --- | --- |
| `AGENTS.md` | a table of the members, the rules that matter from the top (each directory's `AGENTS.md` applies inside it and is read before working there; a directory's commands run from inside it), how an API change flows from the backend to the apps, then each member's section | everything outside the block between `<!-- madrileno:workspace begin -->` and `<!-- madrileno:workspace end -->`; a file without the block gets it appended |
| `CLAUDE.md` | `@AGENTS.md`, for Claude Code | the rest of an existing file; the line is added only when missing |
| `.agents/skills/<name>` | a link to each skill of each member, e.g. `../../item-library/.agents/skills/backend-add-module` | entries that are not links into a member's skills |
| `.claude/skills` | a link to `.agents/skills` | |
| `.mcp.json` | the MCP servers the members declare in their own `.mcp.json`, for Claude Code | other servers and keys |
| `.codex/config.toml` | the same servers as `[mcp_servers.<name>]` tables, for Codex | other tables and settings |

It refuses, with exit 2 and the path, when it would have to replace something it did not write: a real `.claude/skills` directory, a skill directory at the root with the name of a member's skill, two members with a skill of the same name, or two members declaring different servers under one name.

## Members

Every direct subdirectory of the root with a `.agents/workspace.md` is a member. The backend's is:

```markdown
---
role: backend
summary: Scala 3 API on Postgres (http4s, skunk), port 9000
---
* start and stop: `(cd {dir} && ./scripts/app.scala start)`, `... stop`
* produces the API contract: the router specs regenerate the client view in
  `{dir}/target/baklava/views/client/orpc/src`
* rules and skills: `{dir}/AGENTS.md`
```

`role` and `summary` fill the table; `role` is one lower-case word, and exactly one member is the `backend`. The body becomes the member's section, with `{dir}` replaced by the member's directory name and `{backend}` by the backend's, so an app can give the exact `sync-contracts` path whatever the directories are called. The web and mobile apps ship their own. To bring in another app of yours, give it a `.agents/workspace.md` and run `generate`.

## Claude Code and Codex

- **Claude Code** reads the root `CLAUDE.md`, and through it `AGENTS.md`, the skills through `.claude/skills`, and `.mcp.json`; it asks once to approve the project's MCP servers, as in a single repo (see [ai-assisted-dev.md](ai-assisted-dev.md#how-claude-code-connects)).
- **Codex** reads `AGENTS.md` and the skills under `.agents/skills`. It reads `.codex/config.toml` only for a trusted project, so add the root to your `~/.codex/config.toml`:

  ```toml
  [projects."/path/to/my-product"]
  trust_level = "trusted"
  ```

  This works whether or not the root is a git repository. Trusting the root does not cover a member you open on its own, which needs its own entry, and a `-c` override on the command line does not count as trust. `codex mcp list`, run from the root, shows the servers.
- Neither assistant loads a member's `AGENTS.md` until it works in that directory, and Codex not even then when it started at the root. That is why the generated block tells it to read the member's file first.

## In a monorepo

Commit the generated files with the rest, and add the check to its CI, with scala-cli installed:

```bash
(cd item-library && ./scripts/workspace.scala check)
```

## On Windows

The skill links need symlink support. Without it (`core.symlinks=false` in Git for Windows), committed links check out as text files and neither assistant finds the skills. Enable symlinks in Git for Windows, with Developer Mode on; `generate` needs the same to create the links.

## What it doesn't do

- It doesn't remove an MCP server a member stopped declaring; delete that entry from the root's `.mcp.json` and `.codex/config.toml` by hand.
- It doesn't clone, install or start anything. Each member's section says how to start it.
- It doesn't merge the members' `AGENTS.md` files into the root's. They stay where they are, and the root points at them.
