# Dev workflow

The README's Quick Start gets you to a running app. This is everything past that — how to stay productive while writing code.

The whole loop is driven from sbt. Two ways to drive it.

## sbt shell vs `sbt --client`

**Long-lived shell** — open `sbt`, leave it running:

```
$ sbt
> ~reStart
```

This is the default for active development. Compilation is incremental, the JVM stays warm, classpath resolution is cached. First command takes 20–30 seconds; everything afterwards is fast.

**`sbt --client`** — connect to the same running server from another terminal:

```bash
sbt --client compile
sbt --client "testOnly madrileno.auction.services.AuctionServiceSpec"
sbt --client scalafmtAll
```

Use this when you have an sbt shell open in one terminal and want to run a one-shot command in another. Falls back to starting a server if none exists, so the first invocation is slow; everything after is sub-second. The CLAUDE.md tells the assistant to use `--client` so it doesn't spin up a new sbt every time.

**Plain `sbt <command>`** — starts sbt, runs the command, exits. Slow (full classpath resolution on every invocation). Reserve for shell scripts and CI.

## Watch loops

`~<task>` runs the task whenever a watched source changes:

| Command         | What it does                                                                  |
| --------------- | ----------------------------------------------------------------------------- |
| `~reStart`      | Recompile, kill the old app, start a new one. The default dev loop.           |
| `~test`         | Recompile, run the full test suite. Useful when refactoring across modules.   |
| `~testOnly Foo` | Like `~test` but scoped to one spec (or a glob: `*AuctionService*`).          |
| `~compile`      | Just type-check. Fast feedback when you don't care about test outcomes yet.   |

Watch is on Scala/SBT/HOCON files only — not `.env`, not docker-compose state. Restart sbt for those.

`reStart` (no `~`) is a one-shot run. Use it when you want to launch the app once for a manual smoke test without the rebuild-on-save behavior.

## Running tests

```
> test                                     // full suite (~7s on a warm JVM)
> testOnly madrileno.auction.services.AuctionServiceSpec
> testOnly *Auction*                       // glob matches multiple specs
> testOnly *AuctionServiceSpec -- -z "closes auction"   // one test by name substring
> testOnly *AuctionServiceSpec -- -t "exact test name"  // exact match
> testQuick                                // re-run only failed/never-run since last `test`
```

The `--` separates sbt args from scalatest args. `-z` is substring match on the test name; `-t` is exact match. Useful when you've broken one test and don't want to wait for the whole suite.

Baklava generation is a side effect of the test run — the OpenAPI spec at `/swagger` is regenerated from `*RouterSpec` files. **Use `testFull`, not `test`, for a full run or contract regeneration.** Under sbt 2, `test` is *incremental* (testQuick-like) and its results are cached in a global store (`~/.cache/sbt`, so it survives a `target/` wipe and a fresh sbt server); on a warm cache `test` can skip the router specs — or every suite — and still report success, regenerating an **empty** spec that clobbers the real one. `testFull` is the uncached, run-everything task. `testOnly <pattern>` also always runs (it's an input task, not cached). If `/swagger` looks stale, rerun `testFull`.

Tests use Testcontainers for Postgres / Mailpit / Silo — they spin up their own containers, so the docker-compose dev stack and the test run don't share state. Telemetry is stubbed with noop tracer/meter in tests; there's no OpenObserve container.

## Code quality

Run before every commit (the assistant does this automatically per CLAUDE.md):

```
> scalafmtAll                              // format every Scala file
> scalafixAll                              // run scalafix rules (organize imports, etc.)
```

Both are idempotent and fast. `scalafmtCheckAll` is the read-only version — fails if anything is unformatted. CI runs the check variant.

The build defines `verifyAll` as a CI proxy:

```
> verifyAll
```

This runs `scalafmtSbtCheck` + `scalafmtCheckAll` + `compile` + `test` in sequence — the same gates CI runs. If `verifyAll` passes locally, your push will pass CI (modulo flaky tests).

## Compiler strictness

The build uses `sbt-tpolecat`. Default mode is `ci`: `-Werror` on, full warnings. Local and CI run the same flags, so warnings that fail CI also fail your local `~reStart`. This catches issues early — the alternative ("relaxed local, strict CI") regularly produced PRs that compiled on the author's machine and failed in CI on warnings they never saw.

If the constant `-Werror` interruptions during exploratory work outweigh catching issues early, opt in to relaxed mode by uncommenting `SBT_TPOLECAT_DEV=true` in your `.env`. Remember to bounce sbt — `sbt-dotenv` reads `.env` only at startup, not on `~reStart`. And keep an eye on CI: anything you missed locally still fails there.

To temporarily reproduce CI strictness when you're in relaxed mode and want a final sanity-check before pushing:

```bash
SBT_TPOLECAT_DEV= sbt --client compile
```

## Database tasks

Apply pending migrations:

```
> runMain madrileno.main.MigrateMain
```

That's the app's own `IOApp` — the same one the Docker image ships as `bin/migrate-main` for production deploys ([deployment.md](deployment.md)) — so it reads `application.conf` with `.env` already injected (`PG_HOST` / `PG_PORT` / `PG_DATABASE` / `PG_USER` / `PG_PASSWORD`). It takes a subcommand; `migrate` is the default:

```
> runMain madrileno.main.MigrateMain info       // list applied / pending migrations
> runMain madrileno.main.MigrateMain validate   // verify checksums match the files
> runMain madrileno.main.MigrateMain clean      // DROP every table; only on a dev DB
```

A single env-correct entry point — there's no `flyway-sbt` plugin to fall back to (its tasks read `sys.env` at build-load time, before sbt-dotenv injects `.env`, so they couldn't see your `PG_*` anyway).

Seed dev data:

```
> runMain madrileno.main.SeedMain
```

`SeedMain` is an `IOApp` mirroring `MigrateMain`'s shape. Refuses to run unless `app.environment == Environment.Dev`. Ships with 3 demo users (`demo@`, `alice@`, `bob@` at example.com) each with a matching `UserAuth` row for `Provider.Dev`, so `POST /v1/auth/dev` with body `{"email": "demo@example.com"}` logs you in as the seeded UUID immediately — no auto-creation needed. The seed body is at the bottom of `SeedMain.scala` — idempotent (find-or-create on stable UUIDs, with reconciliation for pre-existing `UserAuth` rows pointing at different user_ids). Edit it to add your own records.

Run a migration after:
- Adding a migration under `src/main/resources/db/migration/`.
- `docker compose down -v` (which wipes the volume).

The app does **not** auto-migrate on startup. Migrations are an explicit deploy step; running them by accident on prod has bitten people.

## IDE setup

- **Metals (VS Code)** — `Build: Import build` once after cloning. Metals reads the bloop config sbt generates. After adding a dependency in `build.sbt`, run `Build: Import build` again (or use the `import-build` MCP tool if you're driving via the assistant).
- **IntelliJ** — File → Open → select the project root → "Open as sbt project". Let it sync. Re-sync after dependency changes.

Either way, sbt itself doesn't need to be running for the IDE to work — they read the build separately.

## Common stuck states

**`~reStart` doesn't see a file change.**
Sbt's file watcher occasionally drops events on case-only renames or rapid saves. Hit Enter in the sbt shell to force a re-trigger; if that fails, exit (`exit` or Ctrl-D) and re-launch.

**Compile errors that don't go away after fixing the source.**
Stale incremental compile state. Run `clean` then `compile`. If it still fails, kill any running sbt server (`pkill -f sbt-launch` or remove `~/.sbt/1.0/server/`) and start fresh.

**`.env` change not picked up.**
The dotenv plugin reads `.env` once at JVM startup. Restart sbt.

**sbt or compilation dies with `OutOfMemoryError: Java heap space`.**
The sbt JVM's heap lives in `.jvmopts` (committed) — bump `-Xmx` there and restart sbt. Setting `-Xmx` / `SBT_OPTS` in `.env` does nothing: `sbt-dotenv` injects `.env` into the *already-running* JVM, long after `-Xmx` is fixed at launch.

**A test that passes locally fails in CI on a warning.**
You've opted into `SBT_TPOLECAT_DEV=true` and the local build is in relaxed mode. Reproduce CI strictness with `SBT_TPOLECAT_DEV= sbt --client compile`, or comment out `SBT_TPOLECAT_DEV` in your `.env` to revert to the strict default.

**A migration fails with a checksum mismatch.**
You edited a migration that's already been applied. Either roll back the file change or `runMain madrileno.main.MigrateMain clean` your dev DB and re-migrate (only on dev — never on shared environments).

**OpenObserve traces tab is empty.**
Streams are created on first ingest; hit the app a few times, refresh, give it a moment. If it's still empty, check `OTEL_EXPORTER_OTLP_*` in `.env`.

**`/v1/auth/firebase` returns 503 (`provider-unavailable`).**
Firebase auth is only enabled when `FIREBASE_PROJECT_ID` is set; the stock `.env` leaves it blank, so the app boots but Firebase login is off. Set it to your Firebase project id — or, in dev, use `POST /v1/auth/dev` with `{"email":"you@example.com"}` (see below), which logs you in (creating the user) with no provider config at all.

**`/v1/auth/dev` returns 404 (`unknown-provider`).**
Dev login is explicit opt-in, gated on `DEV_AUTH_ENABLED=true` (default `false` in `application.conf`; the stock `.env` sets it). Set it to `true` in your local `.env` if you need the route active. **Never enable in deployments that face real users** — `DevAuthVerifier` mints a session for any email-shaped string, no credential check.

## One-shot recipes

```bash
# full clean validation — `clean` wipes target/ but NOT sbt 2's global cache (~/.cache/sbt),
# so use testFull (not test) to actually run everything
sbt --client clean compile testFull

# run only auction-related specs
sbt --client "testOnly *Auction*"

# run a single test by substring
sbt --client "testOnly *AuctionServiceSpec -- -z 'closes auction'"

# regenerate OpenAPI without running every test
sbt --client "testOnly *RouterSpec"

# wipe dev DB and start over
docker compose down -v && docker compose up -d && sleep 2 && sbt --client "runMain madrileno.main.MigrateMain"
```

## Adding a dependency

1. Edit `build.sbt`.
2. In Metals: `import-build`. In IntelliJ: re-sync. In sbt: the running shell picks up `build.sbt` changes on the next command (it'll print `[info] build.sbt has been changed; loading new project ...`).
3. Compile to confirm resolution succeeds.
4. The `find-dep` and `inspect` MCP tools (if you're driving via the assistant) are quicker than browsing Maven Central manually.

## Where to look next

- [configuration.md](configuration.md) — `.env` mechanics, why restarting sbt picks up changes.
- [database.md](database.md) — what migrations look like, how to add one.
- [observability.md](observability.md) — OpenObserve URLs, what each tab shows.
- The root [`README.md`](../README.md) — first-touch Quick Start, dev-stack ports, gotchas.
