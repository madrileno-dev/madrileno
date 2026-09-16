# Documentation

Reference material for working on Madrileno. The root [`README.md`](../README.md) is for getting the app running; this folder is for understanding what makes it tick once it is.

Skim the **Start here** path on day one. Reach for the rest by topic.

## Start here

- [`../README.md`](../README.md) — Quick Start: dev stack, `.env`, migrations, `~reStart`.
- [`dev-workflow.md`](dev-workflow.md) — day-to-day sbt ergonomics, common stuck states.
- [`adding-a-module.md`](adding-a-module.md) — vertical-slice walkthrough from migration to OpenAPI.

## Core

- [`principles.md`](principles.md) — the five principles the codebase is built around.
- [`architecture.md`](architecture.md) — `Main` → `ApplicationLoader` → modules; what gets wired where.
- [`module-anatomy.md`](module-anatomy.md) — module shape, providers (`RouteProvider` & friends), cross-module dependencies.

## Stack

- [`http.md`](http.md) — http4s + stir + baklava routing layer; `BaseRouter`, error helper, OpenAPI generation, admin gate, pagination (offset + cursor).
- [`api-versioning.md`](api-versioning.md) — path-prefix versioning via the `ApiVersion` enum; how to add `v2`, which routes stay unversioned, deprecation patterns.
- [`json.md`](json.md) — `JsonProtocol` mixins, opaque-type codecs, snake_case vs camelCase, custom decoders.
- [`database.md`](database.md) — Skunk sessions vs transactions, table definitions, repository DSL, migrations.
- [`auth.md`](auth.md) — Firebase + JWT + refresh tokens; `AuthContext`; the `ExternalAuthVerifier` swap point.
- [`scheduler.md`](scheduler.md) — db-scheduler-inspired Postgres scheduler; recurring + one-time tasks; admin UI.
- [`mailer.md`](mailer.md) — render → serialize → schedule → SMTP; scalatags templates; dev previews.
- [`events.md`](events.md) — the two event channels (transient bus vs durable outbox) and how to pick one.
- [`event-bus.md`](event-bus.md) — local fs2 Topic vs Postgres LISTEN/NOTIFY; the `AuctionEvent` worked example.
- [`outbox.md`](outbox.md) — transactional outbox: durable domain events, delivery ledger, retries, dead-letters, replay.
- [`websockets.md`](websockets.md) — `WsRouteProvider`; projecting internal events to public envelope DTOs.
- [`external-apis.md`](external-apis.md) — sttp client stack and the trait + `Live` class gateway pattern.
- [`file-storage.md`](file-storage.md) — disk + S3-compatible object storage; auction images as the worked example.
- [`imaging.md`](imaging.md) — image utilities (info / EXIF / convert / resize / cover / crop / rotate / strip).
- [`cache.md`](cache.md) — `CacheRuntime` over Caffeine; per-JVM scope; multi-instance considerations.
- [`rate-limiting.md`](rate-limiting.md) — fixed-window directive, per-route buckets, IP / user / header discriminators.
- [`feature-flags.md`](feature-flags.md) — typed variants, targeting rules + segments, percentage rollouts, admin API + audit, client bootstrap; the auction showcase.

## Tooling

- [`scripts.md`](scripts.md) — scala-cli scripts under `scripts/`: `init-project` (rename template), `scaffold-module` (vertical-slice generator), `dev-console` (REPL with the wire graph live).
- [`mcp.md`](mcp.md) — `scripts/mcp-server.scala` — MCP server exposing this template's docs / source / diffs to an AI assistant for pattern-aware new-module work.
- [`updating-from-upstream.md`](updating-from-upstream.md) — pulling upstream template changes into a derived project after it has drifted; the triage loop and what the `.madrileno-ref` pin means.
- [`ai-assisted-dev.md`](ai-assisted-dev.md) — the Metals + madrileno MCP server setup for driving development with Claude Code (the build tools `CLAUDE.md` assumes).

## Conventions

- [`domain-modeling.md`](domain-modeling.md) — opaque types, enums, smart constructors; behaviour-on-values rules.
- [`sealed-monad.md`](sealed-monad.md) — result-ADT pipelines; result vs error vocabulary; keep it local to one method.
- [`error-handling.md`](error-handling.md) — domain rejections vs infrastructure errors; RFC 9457 envelope; compensation patterns.
- [`testing-guide.md`](testing-guide.md) — testing philosophy, fixtures, baklava router specs, Testcontainers.

## Operations

- [`configuration.md`](configuration.md) — pureconfig + HOCON + `.env`; the override pyramid.
- [`observability.md`](observability.md) — OpenTelemetry traces / metrics / logs; trace-ids in error responses.
- [`deployment.md`](deployment.md) — sbt-native-packager Docker image; the env-var contract; migrations as a separate step.

## Frontend

- [`frontend.md`](frontend.md) — the reference frontend companion (a separate repo); the generated-contract loop that keeps it type-safe against the router specs.
