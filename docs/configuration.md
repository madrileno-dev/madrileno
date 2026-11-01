# Configuration

Three pieces:

- **HOCON** (`src/main/resources/application.conf`) — defaults, structure, env-var hooks.
- **pureconfig** — strongly-typed loaders. Case classes `derives ConfigReader`, sliced out of the config tree with `config.at("section").loadOrThrow[T]`.
- **`.env` file** — local dev only. The `sbt-dotenv` plugin reads it and exports the variables into the JVM's environment when sbt starts. Production uses real environment variables.

The application doesn't read `.env`. It reads `application.conf`. `application.conf` reaches into the JVM environment via `${?VAR}` substitution, and `sbt-dotenv` is what ensures those variables exist locally without you having to `export` them by hand.

## The layering

A typical setting looks like this in `application.conf`:

```hocon
http {
  port = 9000
  port = ${?PORT}
}
```

Two things happen in order:

1. `port = 9000` sets the default.
2. `port = ${?PORT}` overrides the value if `PORT` is set in the environment. The `?` makes the substitution optional — if `PORT` isn't defined, the line is a no-op and the default stays.

Without the `?`, an undefined env var would crash the application at startup. With it, the env var is purely an override.

This pattern is everywhere: defaults are committed to the repo, env vars are the per-environment overrides, no separate `application-prod.conf` to keep in sync. To inspect what's overridable, grep for `${?` in `application.conf`.

`.env.sample` shows the variables a typical setup needs; copy to `.env` and adjust.

## Slicing the config

`Main` builds one `ConfigSource` and passes it to `ApplicationLoader`:

```scala
config <- Resource.eval(IO.delay(ConfigSource.default))
```

`ConfigSource.default` reads HOCON from `application.conf` (and any layered files Typesafe Config picks up — `application.json`, `application.properties`, JVM `-D` properties).

From there, every consumer slices the part it cares about:

```scala
val pgConfig: PgConfig         = config.at("pg").loadOrThrow[PgConfig]
val httpConfig: HttpConfig     = config.at("http").loadOrThrow[HttpConfig]
val schedulerConfig            = config.at("scheduler").loadOrThrow[SchedulerConfig]
```

`config.at("pg")` is itself a `ConfigSource` — a window onto the `pg { ... }` subtree. `loadOrThrow[T]` materializes it with the given `ConfigReader[T]`.

You can also load a single field: `config.at("logging.loglevel-request-response").loadOrThrow[Int]`.

## Defining a config case class

Plain case class + `derives ConfigReader`:

```scala
final case class HttpConfig(
  host: Ipv4Address,
  port: Port,
  maxRequestSize: Long,
  baseUrl: URI
) derives ConfigReader
```

Three rules to know:

- **Field names use camelCase; HOCON keys use kebab-case.** `maxRequestSize` ↔ `max-request-size`. pureconfig translates automatically.
- **Defaults in the case class become defaults in the config.** A field with a Scala default is optional in HOCON. The `application.conf` defaults are what's documented; the case-class defaults are the fallback when even the HOCON file doesn't mention the key.
- **`Option[T]` fields are nullable.** Use them for genuinely optional settings (`MailerConfig.username` — dev SMTP doesn't need auth).

For Scala 3 enums loaded as a HOCON string, derive `EnumConfigReader` (not plain `ConfigReader`):

```scala
import pureconfig.generic.derivation.EnumConfigReader

enum Environment derives EnumConfigReader {
  case Dev, Test, Staging, Prod
}
```

Case names map PascalCase → kebab-case at the HOCON boundary (`Dev` ↔ `"dev"`). Plain `derives ConfigReader` on an enum doesn't work — pureconfig treats it as a sum type and expects an object with a `type` discriminator, not a flat string.

Where derivation can't be inferred (older patterns, Scala 2 holdouts, manual control), `pureconfig.generic.semiauto.deriveReader` is the explicit form for case classes — same result as `derives ConfigReader`, more explicit:

```scala
given ConfigReader[PgConfig] = deriveReader[PgConfig]
```

Both are in use; either is fine.

## Special types

| Type                          | From                      | What it parses        |
| ----------------------------- | ------------------------- | --------------------- |
| `Ipv4Address` / `Port`        | `pureconfig-ip4s`         | `"0.0.0.0"` / `9000`   |
| `URI`                         | `pureconfig-core`         | `"http://localhost:9000"` |
| `Duration` / `FiniteDuration` | `pureconfig-core`         | `"30s"`, `"5m"`, `"PT5M"` (ISO 8601) |
| Scala 3 enums                 | `pureconfig-generic-scala3` | kebab-case of case name (`Dev` → `"dev"`) via `derives EnumConfigReader` — see Environment above |

All three pureconfig modules are pulled in via `build.sbt`:

```scala
"com.github.pureconfig" %% "pureconfig-core"           % pureconfigV
"com.github.pureconfig" %% "pureconfig-ip4s"           % pureconfigV
"com.github.pureconfig" %% "pureconfig-generic-scala3" % pureconfigV
```

Need a type pureconfig doesn't ship a reader for? Define a `ConfigReader[T]` and put it in `T`'s companion. The compiler finds it automatically.

## Where to load

The convention is: **load close to where the config is used.** The shape of which-class-loads-which-config falls out of that:

- `Main` loads anything required to construct resources before `ApplicationLoader` (`AppConfig`, `PgConfig`, `SchedulerConfig`, `StorageConfig`). These shape what gets wired before module code runs.
- `ApplicationLoader` loads things that *every module* might want (`HttpConfig`, `AppConfig` again, `AdminConfig`, `MailerConfig`).
- A module loads its own slice (`AuthModule` reads `jwt`, `firebase`, `dev-auth`, and `oidc`).

`AuthModule`'s example:

```scala
trait AuthModule extends … {
  val config: ConfigSource

  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  // …
  private val firebaseConfig = config.at("firebase").loadOrThrow[FirebaseConfig]
}
```

The module gets `config: ConfigSource` from `ApplicationLoader` (which received it from `Main`). It only loads what it needs. Pure modules don't need a `ConfigSource` at all.

Don't pre-load everything in `Main` and pass typed configs everywhere — that pushes every module's dependency into `Main`'s signature. The `ConfigSource` is the lightweight shared handle; it's the typed slices that each module carries.

## Validation

`ConfigReader` fails fast on missing keys, type mismatches, and (for ip4s) malformed addresses. Beyond that, add `require(...)` in the case class body for invariants:

```scala
final case class StorageConfig(maxFetchBytes: Long, objectStorage: S3Config) derives ConfigReader {
  require(maxFetchBytes >= 0, s"storage.max-fetch-bytes must be >= 0, got $maxFetchBytes")
}
```

`require` runs at construction time, so misconfiguration crashes startup with a meaningful message instead of producing wrong behavior at runtime. Use it when "negative number" or "empty string" or "host without port" doesn't make sense — anywhere the type system can't already eliminate the bad case.

## What's in `application.conf`

| Section          | Loaded by                            | Notes                                                             |
| ---------------- | ------------------------------------ | ----------------------------------------------------------------- |
| `app`            | `Main`, `ApplicationLoader`          | Service name, env tag (`dev`/`prod`), version, API version prefix |
| `http`           | `ApplicationLoader`                  | Bind host/port, max request size, public `baseUrl`                |
| `pg`             | `Main`                               | Postgres connection pool params                                   |
| `scheduler`      | `Main`                               | Polling, retry backoff, heartbeat                                 |
| `mailer`         | `ApplicationLoader`                  | SMTP host/port/credentials/from                                   |
| `logging`        | `ApplicationLoader`                  | Outbound HTTP request/response log level                          |
| `firebase`       | `AuthModule`                         | Firebase project id (`FIREBASE_PROJECT_ID`); Firebase auth is on when set |
| `oidc`           | `AuthModule`                         | OIDC providers — HOCON map `oidc.providers.<name>` and/or env single slot (`OIDC_PROVIDER_NAME` / `OIDC_ISSUER` / `OIDC_AUDIENCE` / `OIDC_JWKS_URI`) |
| `jwt`            | `AuthModule`                         | Signing secret + token TTL                                        |
| `admin`          | `ApplicationLoader`                  | Basic Auth user/password for `/admin/*`                            |
| `storage`        | `Main`                               | Object store: `max-fetch-bytes` cap + `object-storage.*` S3 creds |

If you add a new top-level section, follow the same shape: defaults in `application.conf` with `${?VAR}` env hooks, a case class `derives ConfigReader`, loaded in the module that needs it.

## `.env` and the dev workflow

`project/plugins.sbt`:

```scala
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.2.0")
```

When `sbt` (or `sbt --client`) starts, the plugin reads `.env` from the project root and merges it into the JVM environment. By the time `ConfigSource.default` resolves `${?PG_HOST}`, the variable is there — same as if you'd `export`ed it.

Two consequences:

- **`.env` is dev-only.** Don't deploy it. Production uses the real environment (Docker `--env`, k8s `env:`, systemd `Environment=`, your secrets manager, whatever).
- **Restart sbt to pick up `.env` changes.** The plugin reads it once at startup. `~reStart` watches Scala/HOCON files, not `.env`. `./scripts/app.scala start` reads `.env` itself at every start, so an app run through the launcher needs only a `restart`.

`.env.sample` is what's checked in. Copy to `.env` for local work. Don't commit `.env` itself — it's in `.gitignore`.

## Inspecting at runtime — `/admin/config`

The merged config (after HOCON layering + env substitution) is exposed at `GET /admin/config` — Basic-Auth gated like the other `/admin/*` endpoints. Useful for the "what is this process actually running with?" question that's otherwise impossible to answer without SSHing in and grepping.

```bash
curl -u admin:admin http://localhost:9000/admin/config
```

Returns a JSON tree mirroring the HOCON structure. **Secrets are redacted**: leaf values whose key name contains `password`, `passphrase`, `secret`, `credential`, `access-key`, `api-key`, `private-key`, or `token` (case-insensitive) come back as `"[REDACTED]"`. Sub-objects under keys like `refresh-token` are walked, not redacted wholesale — only primitive leaves get replaced.

For project-specific secrets that don't match the heuristic, add their dotted path to `admin.config.redacted-paths`:

```hocon
admin {
  config {
    redacted-paths = ["custom.api.special-credential", "third-party.client-id"]
  }
}
```

The tree shows what the process is actually running with — `ConfigFactory.load()` is used, so `-Dpg.host=...` JVM flag overrides, layered `application.json`/`application.properties`, and env-var substitutions all show through. The top-level keys are then filtered to those declared in `application.conf`, which keeps JVM internals (`java.*`, `os.*`, `awt.*`) and library `reference.conf` defaults out of the response.

## Refresh in production

There's no live config reload. To change a setting:

1. Update env vars (or the deployment manifest).
2. Restart the process.

This is intentional: anything that's safe to change at runtime should be a database row or feature flag, not a config value. Configuration is the contract between the deploy and the binary; live reload turns small mistakes into incidents.

## Where to look next

- [architecture.md](architecture.md) — `Main` and `ApplicationLoader` pattern; where the loaded config flows.
- [database.md](database.md) — `PgConfig` fields and pool tuning.
- [scheduler.md](scheduler.md) — `SchedulerConfig`; retry backoff knobs.
- [mailer.md](mailer.md) — `MailerConfig` and dev SMTP via Mailpit.
- [deployment.md](deployment.md) — wiring real env vars into the production image.
