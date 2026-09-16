# Scripts

scala-cli scripts under `scripts/` for project lifecycle tasks. Each is self-contained — its `//> using` directives declare deps, scala-cli does the rest. Run them either as executables (`./scripts/X.scala …`) or via scala-cli (`scala-cli run scripts/X.scala -- …`).

## `init-project.scala`

Use this once, right after cloning the template, to turn it into your project. Renames `madrileno` everywhere, swaps the Scala package, drops the auction showcase domain, removes the template's Apache-2.0 `LICENSE` (generated projects may relicense freely — see the README's License section), pins the upstream sha for the MCP server, and (by default) deletes the local `docs/` tree since the MCP serves them from the pinned ref.

```bash
./scripts/init-project.scala wine-cellar
./scripts/init-project.scala wine-cellar --package winecellar
./scripts/init-project.scala wine-cellar --keep-docs              # keep `docs/` locally
```

If you don't pass `--package`, the package is the project name lowercased with non-alphanumerics stripped (`wine-cellar` → `winecellar`).

What it does, in order:

1. **Sanity checks** — refuses to run if `build.sbt` isn't present, if `src/main/scala/madrileno/` is missing (already renamed), or if `git rev-parse HEAD` can't resolve a sha (init must run from a git clone — the sha gets pinned in `.madrileno-ref`).
2. **Deletes the auction demo** — `src/{main,test}/scala/madrileno/auction/` and any Flyway migration whose filename mentions `auction` or `bid`.
3. **Deletes `LICENSE` and the README's License section** — both describe the template, not your project. The README section explicitly grants projects generated via this script the right to relicense with no attribution required, so add whatever license fits yours.
4. **Rewrites file contents** under every text file outside `.git`, `target`, IDE caches, `node_modules`, and `scripts/`:
   - Drops blocks bracketed by `// scripts:auction-block-start` / `// scripts:auction-block-end` — auction-coupled fragments in test support that can't be deleted as whole files (today: two in `TestData.scala`, one in `TestApplicationLoader.scala`, one in `SchedulerAdminRouterSpec.scala`). The markers must occupy their own line — inline mentions in prose are ignored.
   - Drops `import madrileno.auction.*` lines and `with AuctionModule` lines from the loaders' `extends` chains.
   - Renames `madrileno.<X>` package references to `<package>.<X>`.
   - Renames standalone `madrileno` to `<project-name>` (HOCON `app.name`, container names, `OTEL_SERVICE_NAME`, `PG_DATABASE`, `MAILER_FROM_ADDRESS`, README/doc references, etc.).
   - **Skips `docs/mcp.md`** — that file documents the MCP system itself; its `madrileno_*` tool names, the upstream repo URL, and the upstream path examples are intentional and must not be renamed.
5. **Renames the source directories** — `src/{main,test}/scala/madrileno/` → `src/{main,test}/scala/<package>/`.
6. **Writes `.madrileno-ref`** with the upstream URL (derived from `git remote get-url origin`, falling back to the canonical madrileno URL) + the sha resolved at step 1. The MCP server reads this to anchor every tool call to a specific upstream commit. Commit it — your collaborators (and Claude) benefit from a shared pin.
7. **Updates `.gitignore`** — whitelists `.madrileno-ref` (the template's blanket `.*` rule would otherwise hide it) and adds `.madrileno-mcp/` (the shadow clone the MCP server keeps for serving docs/source).
8. **Deletes `docs/`** unless `--keep-docs`. The MCP server serves docs on demand from the pinned ref; most projects don't need them in-tree.
9. **Rewrites `docs/<X>` link targets** across every `*.md` in the project tree (outside `.git`, `target`, IDE caches, `node_modules`, `scripts/`) to point at upstream at the pinned sha (e.g. README's reference-section links become `https://github.com/madrileno-dev/madrileno/blob/<sha>/docs/<X>`). Matches any link target starting with `docs/` — `.md`, images, anything. Only runs when docs were deleted, so the links stay clickable. Under `--keep-docs` the links stay local.

After running:

```bash
cp .env.sample .env
sbt testFull
```

The init script runs `sbt 'scalafixAll; scalafixAll'` itself before printing the next-steps, because the auction surgery leaves a pile of imports that were used only by the now-deleted methods (e.g. `org.http4s.MediaType`, `madrileno.utils.imaging.*`). Two passes because removing one import can free another. If that step fails (e.g., `sbt` not on PATH), re-run it manually before `sbt testFull`.

If anything's off, `git checkout .` reverts.

### About the marker convention

`// scripts:auction-block-start` … `// scripts:auction-block-end` flags a region that's coupled to the auction demo but can't be deleted as a standalone file (e.g., one method inside a shared `TestData.scala`). The markers are just comments — they have no effect at compile time and live in the source repo permanently. `init-project.scala` deletes whatever's between them (markers included) when it runs.

Adding a new auction-coupled block elsewhere? Bracket it with the same markers and the init script picks it up automatically. Be conservative — every marker is a hard-coded "this is auction-specific, drop on init" declaration that has to stay accurate.

### What it doesn't do

- Doesn't touch your git working tree (no `git add` / `git commit`). Run `git status` after, review, commit yourself.
- Doesn't run `sbt compile`. The compiler is the safety net; running it is your call. (It *does* run `sbt scalafixAll` twice, to clear orphaned imports from the auction surgery — see above.)
- Doesn't rewrite prose. Surviving doc files (under `--keep-docs`) get the `madrileno` → `<name>` substitution, but `README.md`, badges, and any project-specific marketing copy are yours to rewrite. Same for `docs/architecture.md`-style "why we did X" notes — they describe the template's reasoning and might or might not match your own.
- Doesn't delete itself. Once you're done renaming, `rm scripts/init-project.scala` (and this doc, if you want) — the template baggage is yours to keep or trim.

## `scaffold-module.scala`

Generate a new module (aggregate vertical slice) under the project's package — domain, repository, service, router, DTO, module trait, Flyway migration, and three specs (domain, repository, router). Wires the module into `ApplicationLoader`'s `extends` chain and injects a `random<Aggregate>Id()` factory into the shared `TestData` object, both automatically.

```bash
./scripts/scaffold-module.scala Wine wines
```

Two positional arguments:

1. **Aggregate** — PascalCase singular, drives class names: `Wine`, `WineId`, `WineRepository`, `WineModule`, etc.
2. **Plural** — lowercase plural, drives URL segments (`/v1/wines/{id}`) and OpenAPI tags (`tags = Seq("Wines")`).

The script derives the singular lowercase variant from the aggregate (`Wine` → `wine`) and uses it for the module subpackage name, variable names, the SQL table name (`wine`), and the migration filename (`V<N>__wine.sql`). This matches upstream's convention — `V1__user_auth.sql`, `V5__auction.sql`, etc. all use singular table names. The project's root package is auto-detected from the single directory under `src/main/scala/`.

What it does:

1. **Sanity checks** — refuses to run if not in a project root (no `build.sbt` / no `src/main/scala/`), if the project package can't be uniquely identified (must be exactly one directory under `src/main/scala/`), if the templates dir (`scripts/templates/module/`) is missing, if the migration dir (`src/main/resources/db/migration/`) is missing, if `ApplicationLoader.scala` is missing or doesn't contain the expected `HealthCheckModule` anchors, if either target dir (`mainDest`, `testDest`) already exists, if a `<Aggregate>Module.scala` already exists anywhere under `src/main/scala/` (class-name collision with an existing module — e.g. `HealthCheck health_checks` would clash with the built-in `HealthCheckModule`), or if any existing migration already creates a table with the singular's name (e.g. `Auction auctions` when an earlier migration already creates `auction`). All checks run **before** any writes, so a failing precondition leaves the working tree untouched.
2. **Copies the template tree** with placeholder substitution. Files under `scripts/templates/module/main/` go to `src/main/scala/<package>/<aggregate>/`; `test/` goes to `src/test/scala/<package>/<aggregate>/`; `migration/` files become `V<next>__<name>.sql` under `src/main/resources/db/migration/`, where `<next>` is the highest existing `V<N>` plus one.
3. **Auto-wires** by inserting both `import <package>.<aggregate>.<Aggregate>Module` and `    with <Aggregate>Module` into `ApplicationLoader.scala`, anchored on the `HealthCheckModule` import and `with` clauses (always present in the framework's stock loader). Imports are not sorted alphabetically at insertion — `sbt scalafixAll` (recommended in the next-steps printout) reorders them.
4. **Injects the test-data id factory** into `src/test/scala/<package>/support/TestData.scala` — both `import <package>.<aggregate>.domain.<Aggregate>Id` and a `random<Aggregate>Id(): <Aggregate>Id = <Aggregate>Id(randomUuid())` line, anchored on the `UuidV7` import and the `// scripts:scaffold-id-factories` marker. The import is targeted at `<Aggregate>Id` rather than a domain wildcard, so scaffolding a second module can't introduce an ambiguous name into `TestData`. The generated specs call `TestData.random<Aggregate>Id()` instead of minting raw UUIDs, so the output passes the `noRandomUuid` scalafix lint (which bans `UUID.randomUUID` in tests too) out of the box. Like the loader import, the injected import is sorted by `scalafixAll` afterwards. Both anchors are checked in the preflight phase, so a project missing them aborts before any files are written.

After running:

```bash
sbt 'compile; scalafmtAll; scalafixAll'
```

`compile` verifies, `scalafmtAll` formats the generated files, `scalafixAll` reorders the auto-wired import in `ApplicationLoader.scala` (which the script inserts in a fixed position rather than guessing alphabetic order). The generated module compiles green out of the box.

What the scaffold ships:

- **Domain** — `id: <Aggregate>Id`, `name: <Aggregate>Name` (an opaque type over `String` with a non-empty validation), and audit fields `createdAt: Instant`, `updatedAt: Instant`, `deletedAt: Option[Instant]` on the case class itself (the auction module's convention). A `<Aggregate>.create(id, name, now)` smart constructor on the companion sets audit fields. A `rename(newName, now): Either[RenameRejection, <Aggregate>]` behavior method bumps `updatedAt` — the worked example for the behavior-on-values pattern; replace it with your aggregate's real transitions.
- **Repository** — `save`, `find`, `softDelete`, and the auction-style `update[E](id, f: <Aggregate> => Either[E, <Aggregate>]): DBInTransaction[Option[Either[E, <Aggregate>]]]` that does `SELECT … FOR UPDATE` and persists atomically on `Right`. The `name` field is a starter — feel free to delete or rename. Add more domain fields by editing the case class, the `Row`, the `Table` mapping, the DTO, and the migration in lockstep.

### Placeholder substitution

The templates use these placeholders (substituted both in filenames and contents):

- `__Aggregate__` → the PascalCase argument (`Wine`)
- `__Aggregates__` → capitalized plural, derived from the plural argument (`Wines`) — used for OpenAPI tags
- `__aggregates__` → the plural argument (`wines`)
- `__aggregate__` → the lowercase singular, derived (`wine`)
- `__package__` → the auto-detected project package (`madrileno` in the template, your own name after `init-project.scala`)

Substitution order is preserved (longer plural forms before shorter singular forms), but with the current placeholders the order is cosmetic — the trailing `__` on each token means `__aggregate__` is not a substring of `__aggregates__`, so neither shadows the other regardless of pass order. The convention is there as a habit if you add overlapping placeholders later.

### What it doesn't do

- Doesn't generate a service spec. The service is a thin wrapper around the repository in the scaffold; add a spec when there's real service logic.
- Doesn't enforce ownership / authorization. The generated router takes the `AuthContext` (so the route is gated by the framework's auth gate) but doesn't yet scope queries by `authContext.userId` — that's where you fill in the domain rules.
- Doesn't run `sbt compile`, `scalafmtAll`, or `scalafixAll`. All three are bundled into one command in the next-steps printout; `scalafixAll` is the one that sorts the auto-wired import in `ApplicationLoader.scala`.

## `dev-console.scala`

Boots a Scala 3 REPL with the project's wire graph live — the `ApplicationLoader` is constructed against the real dev Postgres / sttp HTTP client / scheduler / S3 / event bus, and bound to `app` at the prompt. `run(io)` executes an `IO[A]` synchronously, `db(action)` does the same inside a Skunk session.

```bash
./scripts/dev-console.scala
```

The first `app` reference at the prompt boots the application (scala-cli REPL initialises top-level vals lazily). The banner fires then. Subsequent references are free. Example:

```
scala> db(app.userRepository.find(UserId(UUID.fromString("..."))))
madrileno dev console — env=Dev
  app        the ApplicationLoader (transactor, repositories, services)
  run(io)    execute an IO[A] and return A
  db(action) execute a DB[A] inside a session
val res0: Option[User] = Some(User(...))
```

### How it works

The wrapper depends on a cached classpath at `target/console-classpath`, written by a hook on the `Compile / compile` task in `build.sbt`. Compile is the right trigger because the cached classpath includes `target/scala-3.8.2/classes` — the project's own compiled output — so the file is only useful after a successful compile.

- Fresh clone: run `sbt compile` once. Resolves deps, compiles project, writes the cache.
- Source change: next `sbt compile` (including via `~reStart` / `~test`) refreshes the cache.
- Deps change in `build.sbt`: same — next `sbt compile` resolves new deps and rewrites the cache.
- Daily dev: nothing extra needed; the file stays valid as long as compile is current.

If the cache is missing, the wrapper prints `run \`sbt compile\` first` and exits non-zero.

Boot time: `./scripts/dev-console.scala` returns a REPL prompt in ~5s (scala-cli compile + JVM warmup). The actual `ConsoleApplication.boot()` (DB pool, HTTP client, scheduler, S3 backend, event bus) only runs on first `app` reference and adds ~3-5s to that first call.

### What it doesn't do

- Doesn't enforce a read-only mode. `db(...)` writes are live against the dev DB. There's no audit log of REPL commands. A prod-safe console (read-only by default, `--prod` flag, audit trail) is a separate effort.
- Doesn't auto-refresh the classpath. If you bump a dep and don't recompile, the cached classpath is stale; the wrapper happily uses it and you'll get a `ClassNotFoundException` at boot for the new dep. Run `sbt compile` to refresh.
- Doesn't emit OTel traces. `ConsoleApplication` wires noop `Tracer` / `Meter`, so REPL commands don't clutter the dev OTel pipeline.
- Doesn't emit app logs. scala-cli's REPL launcher bundles `slf4j-api 1.7.x`, which loads ahead of the project's `2.0.x` and finds no matching binder (logback 1.5 ships the 2.x SPI). The `SLF4J: Defaulting to no-operation` warning at startup is the visible side; the practical effect is that logback-routed logs from your code are silently dropped in the REPL. For "what did this service do?" debugging, wrap calls in `run(...)` and inspect the return value directly.

## `doctor.scala`

Smoke-tests the dev setup. Run it after a fresh clone, or whenever something feels off ("which thing is broken?"). Diagnose only — does not start anything for you. Exit code 0 on all-green, 1 if any check fails (so CI can use it as a gate later).

```bash
./scripts/doctor.scala
```

Output on a healthy box:

```
✓ .env present
✓ docker available (Docker version 25.0.2, build 29cf629)
✓ docker compose services up
    madrileno-postgres         Up About a minute (healthy)
    madrileno-mailpit          Up About a minute (healthy)
    madrileno-silo             Up About a minute (healthy)
    madrileno-openobserve      Up About a minute (healthy)
✓ postgres reachable (localhost:55432)
✓ mailpit reachable (http://localhost:58025/api/v1/info → 200)
✓ silo reachable (http://localhost:59000/minio/health/live → 200)
✓ openobserve reachable (http://localhost:55080/healthz → 200)
✓ app responding (http://localhost:9000/v1/health-check → 200)

All 8 checks passed. Happy hacking.
```

On failure, each failed check prints a one-line `Hint:` pointing at the command that fixes it (`cp .env.sample .env`, `docker compose up -d`, `sbt "~reStart"`, etc.). Failures don't cascade — every independent check runs so you see the full picture in one shot, not iteratively.

The checks:

| # | Step | Notes |
| - | ---- | ----- |
| 1 | `.env` present | Hints `cp .env.sample .env`. |
| 2 | `docker` CLI available | Skips compose-services check on failure. |
| 3 | docker-compose services running | Reports each of postgres / mailpit / silo / openobserve and its status. |
| 4 | Postgres TCP socket open on `PG_PORT` | Reads `.env` for the port (falls back to `.env.sample` if `.env` is missing). |
| 5–7 | Mailpit / Silo / OpenObserve HTTP health endpoints | `GET /api/v1/info`, `/minio/health/live`, `/healthz` respectively. |
| 8 | App responding | `GET http://localhost:$PORT/v1/health-check`. Hints `sbt "~reStart"` on failure. |

### What it doesn't do

- Doesn't open a real JDBC connection — just a TCP socket. "Port answers" is enough to distinguish "compose down" from "compose up but something else wrong"; a real connect would need the JDBC driver as a script dep and adds little signal.
- Doesn't auto-fix. "Doctor" is a smoke test, not a setup script. Copy-paste the hint; running the actual command is your call.
- Doesn't check OS-level prereqs (sbt installed, JDK version, etc.). Those would have failed earlier (you couldn't have run this script). If you want a full bootstrap check, that's a different scope.
- Doesn't follow `S3_ENDPOINT` / `OTEL_EXPORTER_OTLP_*_ENDPOINT` from `.env`. The Silo / OpenObserve port checks use the host ports from `docker-compose.yml` (59000 / 55080). Doctor's scope is "is the local dev stack up", not "is whatever the app is configured to talk to up". If you've pointed the app at remote Silo / OpenObserve, the local doctor checks are still meaningful for the dev stack itself; if you've also customised the compose port mappings, doctor needs a matching tweak.

## Template-internal CI workflows

The repo ships four GitHub Actions. `.github/workflows/scala.yml` is the project's own CI (format check, scalafix, compile, test) — kept on init like any other project file. The other three are **template-internal**: they validate template content and are deleted by `init-project.scala` (along with `scripts/check-links.scala`, which only the link-check workflow uses):

- **`.github/workflows/link-check.yml`** — runs `scripts/check-links.scala` on PRs touching markdown. The script walks every `.md`, extracts `[text](target)` links, verifies internal targets exist (relative paths + `#anchor` heading slugs). Externals + `mailto:` / `tel:` are skipped. Code spans + fenced blocks are stripped first so Scala signatures inside code samples don't trip the regex.
- **`.github/workflows/script-tests.yml`** — four jobs:
  - `compile-scripts` — `scala-cli compile` every `scripts/*.scala`, catches dep/import drift in the scripts themselves
  - `run-doctor` — smoke-runs `scripts/doctor.scala`, accepts exit `0` or `1` (CI has no dev stack so doctor's "checks failed" exit is expected; anything else is a real crash)
  - `test-init-project` — `git clone`s the repo into a temp dir, runs `./scripts/init-project.scala wine-cellar`, asserts the rename / auction-drop / workflow-cleanup happened, then `sbt compile`s
  - `test-scaffold-module` — same shape, runs `./scripts/scaffold-module.scala Wine wines`, asserts generated files exist + `WineModule` is wired into `ApplicationLoader`, then `sbt compile`s
- **`.github/workflows/site-dispatch.yml`** — on a push to `main` touching `docs/**` or `README.md`, sends a `docs-updated` repository dispatch to `madrileno-dev/madrileno-dev.github.io` so the website rebuilds. Needs the `SITE_DISPATCH_TOKEN` secret (a fine-grained PAT scoped to the site repo, Contents read/write).

All workflows verify template content (our docs, our scripts). A forked project that wants the same auto-checks can re-add a workflow themselves; `doctor.scala` and the other scripts stay on init as user-facing tools. `check-links.scala` goes — without our `docs/` tree it has nothing meaningful to walk.

## File layout

- `init-project.scala` — standalone, no companion files
- `scaffold-module.scala` + `templates/module/` — generator + templates
- `dev-console.scala` — wrapper. The REPL predef is embedded as a string inside the wrapper (top of the file), written to a temp file at launch and passed to scala-cli's REPL. One file, at the cost of no syntax highlighting on the predef section in most editors.
- `doctor.scala` — standalone, no companion files
- `check-links.scala` — standalone; template-only, deleted on init
