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
- **scala-cli** — [scala-cli.virtuslab.org/install](https://scala-cli.virtuslab.org/install) — needed for the tools under `scripts/` (`app`, `scaffold-module`, `doctor`, `dev-console`, …). Versions before 1.18 print a warning about TASTY files from Scala 3.9 on every run; it is harmless.
- **Docker** with `docker compose` — [docs.docker.com/engine/install](https://docs.docker.com/engine/install/)

The three JVM tools also install together via [SDKMAN](https://sdkman.io/), from the repo root:

```bash
sdk env install        # the JDK pinned in .sdkmanrc, the same file CI reads
sdk install sbt
sdk install scalacli
```

In a new shell, `sdk env` switches back to the pinned JDK.

### 1. Boot the dev stack

```bash
docker compose up -d                  # Postgres only: everything the app needs
docker compose --profile full up -d   # plus Mailpit, OpenObserve and Silo
```

Postgres is the whole default stack. The app stores uploads on disk, writes outgoing mail to its log and keeps telemetry off until you ask for more, so a clone runs with one container and nothing to configure. The `full` profile adds the three services behind the SMTP, OTLP and S3 integrations; flip the three matching lines in `.env` (`MAILER_SENDER=smtp`, `OTEL_SDK_DISABLED=false`, `OBJECT_STORAGE=s3`) and the same binary uses them.

All host ports are non-standard so they don't clash with anything you already have running:

| Service       | Profile | Image                                          | Host port(s)                              | Login                                |
|---------------|---------|------------------------------------------------|-------------------------------------------|--------------------------------------|
| Postgres      | default | `postgres:18`                                  | `55432` → 5432                            | `postgres` / `postgres`              |
| Mailpit       | `full`  | `axllent/mailpit:latest`                       | `51025` (SMTP), `58025` (UI)              | —                                    |
| OpenObserve   | `full`  | `public.ecr.aws/zinclabs/openobserve:v1.0.4`   | `55080` (UI + OTLP HTTP)                  | `root@example.com` / `Complexpass#123` |
| Silo          | `full`  | `pgsty/silo:RELEASE.2026-09-16T00-00-00Z`      | `59000` (S3 API), `59001` (Console UI)    | `minioadmin` / `minioadmin`          |

State persists across restarts in named volumes. To wipe and start clean:

```bash
docker compose --profile full down -v
docker compose up -d
```

### 2. Configure env

```bash
cp .env.sample .env
```

The sample is wired against the docker-compose ports above, including a working `Authorization` header for OpenObserve so OTLP traces show up in the UI as soon as you enable them. Out of the box it selects the Postgres-only stack: `MAILER_SENDER=log`, `OBJECT_STORAGE=disk`, `OTEL_SDK_DISABLED=true`.

`docker-compose.yml` reads the same `.env`: `PG_PORT`, `PG_DATABASE`, `PG_USER` and `PG_PASSWORD` set up the Postgres container, `MAILER_PORT` is Mailpit's SMTP port, and `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` and `S3_BUCKET` set up Silo. Without a `.env` it falls back to the sample's values. A changed value applies on the next `docker compose up -d`, except that Postgres takes its user, password and database name only when its volume is first created: after changing those, run `docker compose down -v` first. The other ports (`58025`, `55080`, `59000`, `59001`) are fixed in the compose file, and `S3_ENDPOINT` and the `OTEL_EXPORTER_OTLP_*_ENDPOINT` URLs have to match them.

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

Without an sbt shell, from a script or a coding assistant, the launcher does the same as a one-shot: `./scripts/app.scala start` compiles, applies pending migrations, runs the app in the background and returns once the health check answers. `restart`, `stop`, `status` and `logs` do what they say, and `migrate` applies migrations without starting. See [docs/scripts.md](docs/scripts.md#appscala).

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
- **Sent mail** — in the application log (`./scripts/app.scala logs`), one entry per mail with recipients, subject and text body. With the `full` profile and `MAILER_SENDER=smtp`: the Mailpit UI at http://localhost:58025.
- **OpenObserve UI** (traces, metrics, logs; `full` profile and `OTEL_SDK_DISABLED=false`) — http://localhost:55080 → Login → Traces tab. The first OTLP frame from the app creates the streams.
- **Uploads** — under `uploads/` with the default disk backend; in the Silo console at http://localhost:59001 with the `full` profile and `OBJECT_STORAGE=s3`. Presigned uploads (`POST /v1/auctions/{id}/images/presign`) need `s3`; the disk backend has no URLs to sign, so the endpoint answers 500.
- **OpenAPI / Swagger UI** — http://localhost:9000/swagger (dev only — gated on `app.environment=dev`). The spec is generated by Baklava from the router specs on every test run — `sbt test` and `testOnly` refresh the suites that ran and keep the rest. If `/swagger` shows nothing, run `sbt testFull` once: on a fresh checkout sbt 2's cached `test` may run no suites at all (see [docs/dev-workflow.md](docs/dev-workflow.md)).

## Things that catch people out

- **`reStart` reports success even when the app fails to start** (e.g. port 9000 is taken). Check `curl -sf http://localhost:9000/v1/health-check` before assuming it's up; if it never answers, the error is at the end of `logs/madrileno.log`. `./scripts/app.scala start` waits for the health check itself and fails with those lines.
- **sbt caches `.env` at JVM startup.** If you change `.env`, exit sbt and start it again. Same goes for the long-lived sbt server (`~/.sbt/1.0/server/...`). An app started by `./scripts/app.scala` reads `.env` at every start, so there a `restart` is enough.
- **`docker compose down` keeps volumes; `docker compose down -v` wipes them.** Wipe when you want a clean PG, change OpenObserve credentials, or cycle a corrupted state. Add `--profile full` to reach the optional services' containers and volumes.
- **Tests don't use the docker-compose stack.** Testcontainers spins up its own. Don't worry about polluting your dev DB during a test run.
- **The app doesn't run migrations when it starts.** Run `sbt "runMain madrileno.main.MigrateMain"` or `./scripts/app.scala migrate` after adding one or after wiping volumes; `./scripts/app.scala start` runs them before it starts the app.
- **OpenObserve creates OTLP streams on first ingest.** If the Traces tab is empty right after boot, hit the app a few times, refresh, give it a moment. Still empty? `OTEL_SDK_DISABLED` is `true` in the stock `.env`.

## Documentation

Reference material lives in [`docs/`](docs/README.md). For day one, read in this order:

- [`docs/dev-workflow.md`](docs/dev-workflow.md) — sbt ergonomics for day-to-day work; common stuck states.
- [`docs/principles.md`](docs/principles.md) — the five principles the codebase is built around.
- [`docs/adding-a-module.md`](docs/adding-a-module.md) — vertical-slice walkthrough from migration to OpenAPI.

Driving development with an AI assistant? [`docs/ai-assisted-dev.md`](docs/ai-assisted-dev.md) covers the Metals + madrileno MCP server setup (the build tools `AGENTS.md` assumes).

Building a UI against it? [`docs/frontend.md`](docs/frontend.md) covers the reference frontend companion and the generated-contract loop that keeps it type-safe; [`docs/mobile.md`](docs/mobile.md) covers the reference mobile app on the same contract.

The [`docs/README.md`](docs/README.md) lists everything else by topic — stack (auth, scheduler, observability…), conventions (domain modeling, sealed monad, error handling), operations (configuration, deployment).

## License

The template is licensed under [Apache-2.0](LICENSE), but projects generated from it are unencumbered: you may relicense the code created via `./scripts/init-project.scala` under any terms, with no attribution required. The init script removes the LICENSE file (and this section) so you can add a license of your own choosing.
