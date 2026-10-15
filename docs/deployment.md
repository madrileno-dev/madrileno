# Deployment

Slim by design: a single Docker image built by sbt-native-packager, configured by environment variables, and run wherever you run containers — a single VM with Docker is enough, and k8s, ECS or Nomad work just as well.

The repo doesn't ship infrastructure-as-code, a Helm chart, or a CI pipeline. Those are deployment-target-specific; you bring them. What's here is the bit *every* deployment needs: a reproducible image and a clear list of what to feed it.

## Building the image

sbt-native-packager is enabled in `build.sbt`:

```scala
enablePlugins(JavaServerAppPackaging)
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository    := sys.env.get("DOCKER_REPO")
packageName         := "madrileno"
dockerBaseImage     := "azul/zulu-openjdk:21"
Docker / daemonUser := "noroot"
dockerUpdateLatest  := true
```

Two build commands cover the lifecycle:

```bash
# Build the image into the local Docker daemon.
sbt --client "Docker/publishLocal"

# Build and push to the registry pointed at by DOCKER_REPO.
DOCKER_REPO=ghcr.io/your-org sbt --client "Docker/publish"
```

`Docker/publishLocal` produces `madrileno:1.0.0-SNAPSHOT` (matching `version` in `build.sbt`) and `madrileno:latest`. `Docker/publish` is the same plus a push to `$DOCKER_REPO/madrileno:<version>` and `$DOCKER_REPO/madrileno:latest`.

To stamp the image with a meaningful version (git SHA, semver tag, CI build number), pass it through `BUILD_VERSION`:

```bash
docker build --build-arg BUILD_VERSION=$(git rev-parse --short HEAD) ...
```

`build.sbt` exposes that as the `APP_VERSION` env var inside the running container, which `application.conf` then reads via `version = ${?APP_VERSION}`. Anywhere the app reports its version (logs, OTEL `service.version`), the value is whatever you passed.

## What's in the image

- **Base**: `azul/zulu-openjdk:21`. Production-grade JDK, regular security updates, multi-arch.
- **Daemon user**: `noroot`. The app doesn't run as root.
- **Layout**: native-packager's standard — a launcher script under `/opt/docker/bin/madrileno`, JAR + dependencies under `/opt/docker/lib/`, configuration via JVM flags + env vars.
- **Entry point**: `bin/madrileno`. Launches the JVM with the project's `Main`.

The image is *not* multi-stage; it's whatever `JavaServerAppPackaging` builds plus the two extra commands for `BUILD_VERSION`. If you want a slimmer base (Alpine, distroless), swap `dockerBaseImage`. If you want a buildkit-style multi-stage build for shrink, drop the native-packager defaults and write your own `Dockerfile` referencing the universal package output (`sbt --client "Universal/packageBin"`).

## Running the image

The container needs environment variables for every `${?VAR}` substitution in `application.conf`. See [configuration.md](configuration.md) for the full list; the production-relevant subset is:

- **App identity** — `APP_ENVIRONMENT=prod`, `APP_VERSION=<git sha>`.
- **HTTP** — `INTERFACE`, `PORT`, `BASE_URL`, `MAX_REQUEST_SIZE`. Bind to `0.0.0.0` inside the container; expose the port via your load balancer, reverse proxy or orchestrator.
- **Postgres** — `PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER`, `PG_PASSWORD`. Self-hosted or managed (RDS, Cloud SQL, …) — the app only needs a connection. Leave `PG_TELEMETRY_CAPTURE_PARAMETERS` unset: it puts bound query parameters on spans and is a dev-only knob ([observability.md](observability.md#postgres-query-telemetry)).
- **OpenTelemetry** — `OTEL_*` per [observability.md](observability.md). Point at your real OTLP receiver.
- **Mailer** — `MAILER_HOST`, `MAILER_PORT`, `MAILER_USERNAME`, `MAILER_PASSWORD`, `MAILER_FROM_ADDRESS`, `MAILER_TLS=true`. SES, SendGrid, internal SMTP relay — anything that speaks SMTP.
- **Auth** — `JWT_SECRET` (real secret; inject from your secrets manager, never bake into the image). `FIREBASE_PROJECT_ID` if you use Firebase login — that's just the project id (not a secret); ID-token verification fetches Google's public certs. Leave it unset to disable Firebase auth.
- **Object storage** — `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`. Point at real S3 or any S3-compatible store.
- **Admin auth** — `ADMIN_USER`, `ADMIN_PASSWORD`. Gates `/admin/*` (health, jobs UI, mail previews in dev). Strong password.

The dev-stack `.env` is *not* a production template; it's a local-development convenience. For real deploys, generate the env from your platform's secrets store + non-secret config map.

## Migrations are a separate step

The app does **not** auto-migrate on startup. Run them as a discrete deploy step before rolling out the new image:

```
build image → push image → run migrations → roll out the new version
```

The Docker image ships a second launcher, `bin/migrate-main`, that runs Flyway against the same `PG_*` env vars the app reads. Same image, same Git SHA, same config — schema and code can't drift apart. Run it as a one-shot container (or whatever your platform's equivalent is) gated before the new version rolls out.

Two reasons this is the right separation:

- **Migrations are slow and rare.** Running them on every pod start would slow rollouts and make crash-loop debugging painful.
- **Migrations are dangerous.** Locking, long-running, can fail mid-way. They want a single owner per deploy, not N pods racing.

For zero-downtime rollouts, follow the standard expand/contract dance:

1. Migration that's backwards-compatible with the old code (add column, NOT NULL with default).
2. Deploy new code that uses the new shape.
3. Cleanup migration in a later deploy (drop old column, etc.).

## Rolling back

Rollback is redeploying the previous image. Nothing more is needed, provided the two rules above were followed: migrations ran as their own step, and the migration that shipped with the new code was backwards-compatible. The old code then runs happily against the new schema, so rolling back is a routine deploy rather than a special procedure.

The cleanup migration (step 3) is the one that burns the bridge: once the old column is gone, the old image can't come back. Ship it in a later deploy, after the new code has been in production long enough that you won't want to roll back past it.

What the template doesn't do is undo a migration. If one fails partway, Flyway records it as failed and `bin/migrate-main` refuses to run until it's repaired (`bin/migrate-main info` shows which one). Fix forward: correct the migration, clear the failed row from `flyway_schema_history` (Flyway's `repair`; `MigrateMain` doesn't expose it, so add a subcommand or do it by hand), and run it again. Undo scripts are a Flyway Teams feature and aren't wired here.

## Graceful shutdown

On `SIGTERM` the app drains in-flight requests before exiting — rolling deploys don't cut active connections. This is mostly emergent behavior from cats-effect's resource composition; you don't need to do anything to get it.

What happens, in order:

1. The JVM receives `SIGTERM`.
2. `IOApp.Simple` cancels the main fiber. The cancel propagates through the `Resource.use { IO.never }` in `Main.scala`.
3. `Resource` finalizers run in reverse acquire order. So shutdown is **server → scheduler → object store → DB transactor → OTel SDK** — exactly the inverse of how they were started.
4. The Ember HTTP server's release:
   - Stops accepting new connections (new requests get `Connection refused`).
   - Waits up to `http.shutdown-timeout` (default `30s`) for in-flight requests to complete.
   - Any request still in flight after the timeout is force-closed.
5. The scheduler stops polling for new task rows. Tasks already executing run to their next checkpoint.
6. The DB transactor closes the connection pool.
7. The OpenTelemetry SDK's shutdown is called — this flushes pending exporter batches (traces, metrics, logs).

Configure the drain window with `HTTP_SHUTDOWN_TIMEOUT` (HOCON `http.shutdown-timeout`). Pick something a little longer than your worst-case request — for an app that serves p99 = 5s requests, `30s` is plenty. For long-polling or streaming endpoints, raise it. Whatever sends the `SIGTERM` has a "give the process this long to exit" knob (`docker stop --time`, systemd `TimeoutStopSec`, k8s `terminationGracePeriodSeconds`); set it to at least the same value — otherwise it'll `SIGKILL` mid-drain.

What's NOT done out of the box:

- **In-flight scheduler tasks** are hard-canceled with the rest of the resource graph (whatever cats-effect cancellation lets them complete). If you have long-running tasks that need to checkpoint, they should do so frequently rather than relying on a graceful interrupt.
- **OTel exporter flush timeout** is whatever the SDK defaults to. If you're seeing dropped traces at shutdown, configure `OTEL_BSP_EXPORT_TIMEOUT` (env var) higher.

## Health checks

The deep `/admin/health-check` endpoint exists (Postgres + SMTP probe) but it's gated by Basic Auth — designed for human ops checks, not orchestrator probes. For load balancer or orchestrator probes, hit `/v1/health-check` (unauthenticated, lightweight). See [http.md](http.md) and the `HealthCheckModule` for what's wired.

If you need a richer liveness/readiness probe, add an unauthenticated endpoint (or a separate port) that runs the same checks without the auth gate. Don't expose `/admin/*` to the orchestrator.

## What this template doesn't ship

Things you'll likely want and need to add yourself:

- **CI pipeline** — `sbt verifyAll` is the gate locally; wire it (or `compile + testFull + scalafmtCheckAll`) into GitHub Actions / GitLab CI / whatever you use. Use `testFull`, not `test` — sbt 2's `test` is cached and can under-run (see [dev-workflow.md](dev-workflow.md)). The stock workflow in `.github/workflows/scala.yml` also runs the suite under coverage instrumentation, fails below the floor in `build.sbt`, and uploads the HTML report as a workflow artifact.
- **Image scanning** — Trivy, Snyk, your registry's own scanner.
- **Deployment manifests** — compose files, Helm charts, Terraform. Too target-specific to live here.
- **Secrets management** — assumes you have one (AWS Secrets Manager, k8s secrets, Vault). The app reads env vars; the secrets system is responsible for getting them there.
- **Blue/green or canary tooling** — orchestrator-level concern.

The image and the env-var contract are stable; the rest is yours to assemble.

## Where to look next

- [configuration.md](configuration.md) — the full env-var contract.
- [observability.md](observability.md) — what to point `OTEL_*` at; what shows up where.
- [database.md](database.md) — migration mechanics.
- [dev-workflow.md](dev-workflow.md) — running migrations locally (`runMain madrileno.main.MigrateMain`) and its `info` / `validate` / `clean` subcommands.
