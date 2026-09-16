# MCP server

`scripts/mcp-server.scala` exposes the upstream Madrileno reference (at a pinned commit) to an AI coding assistant via the [Model Context Protocol](https://modelcontextprotocol.io). The point: when you ask Claude to "add an `Appointment` module," it can read the canonical `user` and `auction` modules verbatim — opaque types, soft-delete idioms, router shape, spec patterns — and copy the non-obvious bits the scaffold doesn't generate.

It's optional. The template works without it. The MCP earns its keep when you're building new modules, want to learn a less-obvious framework convention, or want to pick up patterns from upstream that landed after you initialized your project.

## What it serves

Six tools, designed around the conceptual units of the codebase (a module, a doc, a path, a diff):

| Tool                       | Returns                                                                                    | Use when                                              |
|----------------------------|--------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `madrileno_overview()`     | Orientation: what madrileno is, reference modules, doc index, pinned ref                   | First call in any session — the anchor                |
| `madrileno_module(name, at?)` | Concatenated source of all main + test files under one module (`user`, `auction`, etc.), at the pinned ref or `at` | Learning a module pattern in full                     |
| `madrileno_doc(name)`      | One doc (markdown), e.g. `auth`, `domain-modeling`, `adding-a-module`                       | Expanding on a concept                                |
| `madrileno_source(path, at?)` | One file, at the pinned ref or `at` (`madrileno.*` package qualifiers rewritten — see below). Fallback for specific paths | "Show me this exact file"                             |
| `madrileno_changes(since?, paths?, target?)` | `git log --oneline` between two refs, optionally path-filtered                | Learning what landed upstream since the project was anchored |
| `madrileno_diff(since?, target?, paths?, format?)` | `git diff` between two refs — `format="stat"` (default) for per-file sizes, `"patch"` for content | Pulling upstream changes — see [updating-from-upstream.md](updating-from-upstream.md) |

Source returned by `madrileno_module`, `madrileno_source`, and `madrileno_diff(format="patch")` is **automatically rewritten** from `madrileno.*` to your project's package. Docs are returned verbatim.

The `at` parameter on `madrileno_module` / `madrileno_source` defaults to the pinned ref; pass `at="origin/main"` (or any ref) to read a different version — the baseline you derived from and upstream-latest, side by side, is the comparison the update workflow runs on.

## How the anchoring works

`init-project.scala` writes `.madrileno-ref` to your project root on init:

```
repo=<your origin URL>
ref=<sha at init time>
```

`repo=` is derived from `git remote get-url origin` (so it works for forks / SSH clones), falling back to the canonical upstream when there's no origin. `ref=` is the sha of HEAD at init time.

The MCP server reads this **once at startup** (lazy val) and anchors every tool call to that commit. If you edit `.madrileno-ref` while the server is running, restart the server for the change to take effect. Commit `.madrileno-ref` — collaborators benefit from a shared pin.

The pin's meaning is **"I have triaged upstream up to here"** — not "I have adopted everything up to here." When you review upstream changes and adopt some but skip others, bump `ref=` to the reviewed sha anyway; a deliberate skip is a decision, and the pin records that it was made. Otherwise `madrileno_changes` re-reports your skips forever. [updating-from-upstream.md](updating-from-upstream.md) builds on this.

The MCP server keeps a local shadow clone of the upstream repo at `.madrileno-mcp/repo/` (gitignored). First launch clones (~50MB, one-time). Every launch does a `git fetch origin` so `madrileno_changes` can compare your pinned ref against the latest `origin/main`.

## Setup

You need [scala-cli](https://scala-cli.virtuslab.org/) on `PATH`. JVM 21 is auto-fetched by scala-cli if needed.

```bash
./scripts/mcp-server.scala
```

First launch downloads dependencies (~1 minute) and clones the upstream repo. Subsequent launches are seconds. The server listens on `http://localhost:8910/mcp`.

## Wiring it into Claude

The template ships a project-scoped `.mcp.json` at the repo root that already points Claude Code at this server (alongside the Metals server — see [ai-assisted-dev.md](ai-assisted-dev.md)). [Project scope is designed to be checked into version control](https://code.claude.com/docs/en/mcp#project-scope), so it's committed and every clone gets the wiring for free. The relevant entry:

```json
{
  "mcpServers": {
    "madrileno": {
      "type": "http",
      "url": "http://localhost:8910/mcp"
    }
  }
}
```

Claude Code picks `.mcp.json` up automatically and prompts once to approve project-scoped servers. (For other clients, the same shape works user-global in `~/.config/claude/mcp.json` or your client's equivalent.) Ask Claude something like *"call `madrileno_overview` and tell me what's available"* to verify the connection.

## A worked scenario

You're building a CRM for a car repair shop. You want an `Appointment` module: customer, vehicle, scheduled time, status.

A concrete prompt to paste into a fresh Claude session (the project's `.mcp.json` must already point at the running server — see above):

> I'm building **garage-crm** — a small CRM for a car repair shop — on top of the madrileno Scala 3 template.
>
> Add the first feature: an `Appointment` module. An appointment has:
>
> - a `customerId` (links to a `User`)
> - a `vehicleId` (opaque type, create it inline — no Vehicle aggregate yet)
> - a `scheduledAt` (Instant)
> - a `status` enum: `Scheduled`, `InProgress`, `Completed`, `Cancelled`
>
> Follow the template's existing module patterns. There's a `madrileno` MCP server connected — use it as your reference. Verify with `sbt compile` when done.

With the MCP wired up, Claude's flow looks like:

1. Calls `madrileno_overview()` — sees the reference modules (`user`, `auction`, `auth`, `healthcheck`), gets the doc index, learns about the scaffold script.
2. Calls `madrileno_doc("adding-a-module")` and `madrileno_doc("principles")` — the conventions (behavior-on-values, sealed-monad, single-aggregate-per-module).
3. Calls `madrileno_module("user")` — the canonical small aggregate (opaque `UserId`, validated opaque `EmailAddress`, repository + filter + soft-delete).
4. Calls `madrileno_module("auction")` — the richer reference (FKs via `ForeignIdTable`, `update[E]` callback with `SELECT … FOR UPDATE`, sealed-monad in the service, behavior methods on the aggregate, `text.asEnum` for status enums).
5. Runs `scripts/scaffold-module.scala Appointment appointments` → skeleton on disk.
6. Customises the domain (adds the status state machine + per-transition rejection ADTs + `Appointment.schedule` smart constructor), repo, service, router, DTO + matching specs, all using the patterns from step 3-4.

The MCP doesn't dictate the answer — it routes Claude to the parts of the reference that matter. What it generates is shaped by the reference, not by guesses.

## Pulling upstream changes

Months after init, upstream Madrileno has evolved. The full workflow lives in [updating-from-upstream.md](updating-from-upstream.md) (also served as `madrileno_doc("updating-from-upstream")`, so the assistant can drive it directly); the shape of it:

```
> madrileno_changes(paths=["src/main/scala/madrileno/auth"])
<your pinned sha>..origin/main (2 commits):

840b2ac auth: provider map refactor + dev login
208deeb Config: type AppConfig.environment as Environment enum

> madrileno_diff(paths=["src/main/scala/madrileno/auth"])          # format="stat" — scope it
> madrileno_diff(paths=["src/main/scala/madrileno/auth"], format="patch")   # content, package-rewritten
> madrileno_module("auth", at="origin/main")                        # whole files when the patch isn't enough
```

Ask Claude to walk through those changes against your auth code and propose updates. After the review, bump `ref=` in `.madrileno-ref` to the sha you triaged up to (even for changes you chose to skip — see above), restart the server, commit. That's the "stay in sync with upstream patterns" loop.

## Refreshing the shadow clone

`git fetch origin` runs on every server startup. If upstream landed something while the server's running and you want to pick it up without restarting, kill and restart the server. (A `madrileno_refresh()` admin tool may land later if this becomes annoying.)

## When not to use it

- Just reading a single doc once: open `https://github.com/madrileno-dev/madrileno/blob/<sha>/docs/<name>.md` in a browser. Faster than spinning up the server.
- The shadow clone is stale (no recent `git fetch` ran) — restart the server.
- Your project diverged so far from madrileno's patterns that the reference no longer maps. At that point the MCP isn't lying, but its suggestions are background noise.

## Known wrinkles

- **HTTP transport, not stdio.** You start the server manually before each Claude session. chimp's only transport today. Documented papercut.
- **chimp is at 0.1.x.** Early-stage MCP library; API may shift. `scripts/mcp-server.scala` will need version bumps occasionally.
- **First-launch is slow.** Java 21 download (if absent) + scala-cli compile + git clone. Subsequent launches are fast.
- **No auth.** The server listens on `localhost`. Don't bind it to public interfaces.

## Where to look next

- [`updating-from-upstream.md`](updating-from-upstream.md) — the catch-up-with-upstream workflow built on `madrileno_changes` / `madrileno_diff`.
- [`scripts.md`](scripts.md) — the rest of the scala-cli scripts under `scripts/` (init, scaffold, dev-console).
- [`adding-a-module.md`](adding-a-module.md) — the vertical-slice walkthrough the MCP's `madrileno_doc("adding-a-module")` returns.
