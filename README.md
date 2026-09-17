# madrileno

An AI-first Scala 3 backend template. Wine auctions are the showcase domain.

AI-first means the repo is wired for assistant-driven development out of the box: a committed `.mcp.json` gives your assistant a live compile/test loop (Metals) and a read-only upstream reference (the madrileno MCP server), and the [`docs/`](docs/README.md) double as its knowledge base. See [`docs/ai-assisted-dev.md`](docs/ai-assisted-dev.md) for the full setup.

If you're using this as a template, run the rename script first — it swaps `madrileno` for your project name + package and removes the auction demo:

```bash
./scripts/init-project.scala <project-name>
```

See [`docs/scripts.md`](docs/scripts.md) for what it does and what else lives under `scripts/`.

## Quick start

You'll need four tools. Install each from its package manager or official installer:

- **JDK 21 (Temurin)** — [adoptium.net/installation](https://adoptium.net/installation/) (apt repo on Linux, `brew install --cask temurin@21` on macOS, winget on Windows)
- **sbt 1.12+** — [scala-sbt.org/download](https://www.scala-sbt.org/download/) (`sbt --version` to check; the launcher reads `project/build.properties` and pulls the pinned sbt itself)
- **scala-cli** — [scala-cli.virtuslab.org/install](https://scala-cli.virtuslab.org/install) — needed for the tools under `scripts/` (`scaffold-module`, `doctor`, `dev-console`, …)
- **Docker** with `docker compose` — [docs.docker.com/engine/install](https://docs.docker.com/engine/install/)

The three JVM tools also install together via [SDKMAN](https://sdkman.io/):

```bash
sdk install java 21-tem
sdk install sbt
sdk install scalacli
```

### 1. Boot the dev stack

```bash
docker compose up -d
```

That brings up four services on non-standard host ports so they don't clash with anything you already have running:

| Service       | Image                                          | Host port(s)                              | Login                                |
|---------------|------------------------------------------------|-------------------------------------------|--------------------------------------|
| Postgres      | `postgres:latest`                              | `55432` → 5432                            | `postgres` / `postgres`              |
| Mailpit       | `axllent/mailpit:latest`                       | `51025` (SMTP), `58025` (UI)              | —                                    |
| OpenObserve   | `public.ecr.aws/zinclabs/openobserve:latest`   | `55080` (UI + OTLP HTTP)                  | `root@example.com` / `Complexpass#123` |
| Silo          | `pgsty/silo:RELEASE.2026-09-16T00-00-00Z`      | `59000` (S3 API), `59001` (Console UI)    | `minioadmin` / `minioadmin`          |

State persists across restarts in named volumes. To wipe and start clean:

```bash
docker compose down -v
docker compose up -d
```

### 2. Configure env

```bash
cp .env.sample .env
```

The sample is wired against the docker-compose ports above — including a working `Authorization` header for OpenObserve so OTLP traces show up in the UI immediately.

`JWT_SECRET` is fine as-is for local dev — change it when you don't want strangers to be able to forge tokens. External auth providers (`FIREBASE_PROJECT_ID`, `OIDC_*`) ship empty; the app boots fine without them and the dev login (`POST /v1/auth/dev`) is enabled by default via `DEV_AUTH_ENABLED=true`.

### 3. Apply database migrations

```bash
sbt "runMain madrileno.main.MigrateMain"
```

`MigrateMain` is the app's own `IOApp` — same as `bin/migrate-main` in the Docker image — so it reads `application.conf` with `.env` injected by sbt-dotenv. Works out of the box on the default `.env`. It takes a subcommand (`migrate` is the default):

```bash
sbt "runMain madrileno.main.MigrateMain info"       # list applied / pending migrations
sbt "runMain madrileno.main.MigrateMain validate"   # verify checksums match the files
sbt "runMain madrileno.main.MigrateMain clean"      # DROP every table — dev DB only
```

Run `migrate` every time you add one under `src/main/resources/db/migration/`.

### 4. Run the app

The recommended workflow is one long-lived sbt session in interactive mode:

```bash
sbt
```

Inside the sbt shell:

```
> ~reStart
```

`~reStart` watches sources and restarts the app on every save (compile-on-save). Plain `reStart` runs it once. The app comes up on `http://localhost:9000` (override with `PORT` in `.env`).

Quick smoke test from another terminal:

```bash
curl http://localhost:9000/v1/health-check
```

To check the whole setup at once — Docker, the compose services, Postgres, and the app — run the doctor (needs scala-cli):

```bash
./scripts/doctor.scala
```

### 5. Look around

- **App** — http://localhost:9000/v1/health-check
- **Mailpit UI** (sent mail lands here in dev) — http://localhost:58025
- **OpenObserve UI** (traces, metrics, logs) — http://localhost:55080 → Login → Traces tab. The first OTLP frame from the app creates the streams.
- **OpenAPI / Swagger UI** — http://localhost:9000/swagger (dev only — gated on `app.environment=dev`). The spec is generated by Baklava as part of `sbt test`; if `/swagger` shows nothing, run `sbt test` once first.

## Things that catch people out

- **sbt caches `.env` at JVM startup.** If you change `.env`, exit sbt and start it again. Same goes for the long-lived sbt server (`~/.sbt/1.0/server/...`).
- **`docker compose down` keeps volumes; `docker compose down -v` wipes them.** Wipe when you want a clean PG, change OpenObserve credentials, or cycle a corrupted state.
- **Tests don't use the docker-compose stack.** Testcontainers spins up its own. Don't worry about polluting your dev DB during a test run.
- **Migrations don't run automatically when the app starts.** Run `sbt "runMain madrileno.main.MigrateMain"` after adding one or after wiping volumes.
- **OpenObserve creates OTLP streams on first ingest.** If the Traces tab is empty right after boot, hit the app a few times, refresh, give it a moment.

## Documentation

Reference material lives in [`docs/`](docs/README.md). For day one, read in this order:

- [`docs/dev-workflow.md`](docs/dev-workflow.md) — sbt ergonomics for day-to-day work; common stuck states.
- [`docs/principles.md`](docs/principles.md) — the five principles the codebase is built around.
- [`docs/adding-a-module.md`](docs/adding-a-module.md) — vertical-slice walkthrough from migration to OpenAPI.

Driving development with an AI assistant? [`docs/ai-assisted-dev.md`](docs/ai-assisted-dev.md) covers the Metals + madrileno MCP server setup (the build tools `CLAUDE.md` assumes).

Building a UI against it? [`docs/frontend.md`](docs/frontend.md) covers the reference frontend companion and the generated-contract loop that keeps it type-safe.

The [`docs/README.md`](docs/README.md) lists everything else by topic — stack (auth, scheduler, observability…), conventions (domain modeling, sealed monad, error handling), operations (configuration, deployment).

## License

The template is licensed under [Apache-2.0](LICENSE), but projects generated from it are unencumbered: you may relicense the code created via `./scripts/init-project.scala` under any terms, with no attribution required. The init script removes the LICENSE file (and this section) so you can add a license of your own choosing.
