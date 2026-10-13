# Testing Guide

## Philosophy

- **Test behavior, not implementation.** Services in this codebase are mostly orchestration — they call repositories, check conditions, combine results. The interesting bugs live at the boundaries (JWT not expiring, filters deleting too many tokens, `send` vs `sendTransactionally`). Tests should catch those, not verify "we called save after create."
- **Real infrastructure where it buys confidence.** Real DB via Testcontainers, real SMTP via Mailpit, real HTTP through the full route stack. Fakes only at unstable or expensive external boundaries (Firebase).
- **Fast feedback despite real infrastructure.** Transaction rollback per test eliminates cleanup and keeps DB tests fast. Testcontainers are shared per suite, not per test.
- **Tests are documentation.** Baklava route tests generate OpenAPI specs and oRPC contracts. Every tested endpoint automatically appears in the API documentation.

## Why Integration Tests Over Fakes for Services

Services could be tested with fake (in-memory) repositories. We chose real DB integration tests instead:

- **Our services are orchestration.** The logic is the flow — create user, save auth record, schedule welcome email, issue tokens — all in one transaction. Testing with fakes would verify "we called the right methods in the right order," which tests the code, not the behavior.
- **The real bugs live at the boundary.** JWT not expiring because `validFor` was ignored. A cleanup job deleting all tokens instead of only expired ones. `send` scheduling mail outside the transaction so it gets delivered even when the transaction rolls back. All were service-to-DB boundary issues. Fakes wouldn't have caught them.
- **Fakes are a second implementation.** They must be maintained alongside real repositories and can silently diverge — SQL edge cases, constraint violations, and transaction semantics are invisible to in-memory fakes.
- **Transaction rollback makes integration tests fast.** No cleanup code, no Docker restart between tests. The speed difference versus fakes is negligible.

**Exception:** If a service grows complex domain logic (pricing calculations, state machines, workflow rules), extract that logic into pure functions and unit test those. Don't force integration tests on pure computation.

## What NOT to Test

- **Framework code** — http4s, circe, skunk, stir are well-tested. Don't re-test their behavior.
- **Macwire wiring** — if it compiles, it works.
- **Configuration loading** — pureconfig handles this; test your config values in integration, not the loading mechanism.
- **External services directly** — Firebase, third-party APIs. Introduce a trait-level test seam instead (see External Service Boundaries below).

## Test Layers

### Layer 1: Unit Tests

Pure functions, no IO, no containers.

Extend `AsyncWordSpec with AsyncIOSpec with Matchers` (or `AnyWordSpec with Matchers` for non-IO tests). Use `TestData` builders for domain objects. Use `TestGivens.fixedClock()` and `TestGivens.deterministicUUIDs()` when deterministic time or IDs matter.

**What belongs here:**
- Domain model logic: `RefreshToken.isValid`, `User.isActive`, `User.withUpdatedProfile`
- JWT encoding/decoding round-trip, expiry validation
- Cron expression parsing and next-time computation
- Email template rendering: subject line, greeting, CTA URL, language-dependent text

**What doesn't:** anything that touches the database, makes HTTP calls, or depends on external services.

### Layer 2: Repository and Service Integration Tests

Real PostgreSQL via Testcontainers. Flyway runs all migrations on startup. Each test suite gets a shared container.

Extend `AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor`.

**What belongs here:**

*Repositories:*
- CRUD lifecycle: create, read, update, soft-delete, verify soft-deleted records are invisible
- Filter queries: construct filters, verify correct rows returned
- Edge cases: update on soft-deleted record is a no-op, `deleteUsedOrDeletedBefore` only deletes used/deleted tokens and preserves active ones

*Services:*
- Full user creation flow: create user + user auth + tokens + welcome email, all transactional
- Refresh token rotation: use token, verify old one is invalidated, verify new one works
- Session revocation: by ID, by user-agent
- Transactional mail scheduling: verify that when user creation rolls back, the scheduled email also rolls back
- Mail serialization round-trip: queued HTML email survives JSON serialization intact

For services that need SMTP (e.g., `AuthenticationServiceSpec`), also mix in `TestMailpit`.

#### Transaction isolation

`TestTransactor` provides two modes:

**`withRollback { ... }`** — wraps the test in a transaction that always rolls back. Zero cleanup, no cross-test pollution. The block receives an implicit `Session[IO]` and `Transaction[IO]`, so repository methods typed as `DBInTransaction` work directly. Use this for most tests.

**`withSession { ... }`** — runs without automatic rollback. Required when the code under test opens its own transactions (e.g., scheduler pick-up, `mailer.sendTransactionally`). These tests must clean up after themselves.

The no-rollback mode is not an escape hatch. It's a first-class testing pattern because several real bugs lived at the transaction boundary — code that appeared correct inside a single transaction but failed across session boundaries.

```scala
class UserRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {
  private lazy val repo = new UserRepository

  "create and get a user" in withRollback {
    val user = TestData.user()
    for {
      created <- repo.create(user, Instant.now())
      fetched <- repo.get(created.id)
    } yield fetched.id shouldBe user.id
  }
}
```

### Layer 3: Route Tests (Baklava)

Test HTTP routes through the real `ApplicationLoader` and generate API documentation from test definitions. Every tested endpoint appears in the OpenAPI spec, HTML docs, and oRPC contracts.

Extend `BaseRouteSpec with TestApplicationLoader`.

**What belongs here:**
- Every HTTP endpoint the application exposes
- Both success and error responses for each endpoint
- Route-level validation: missing fields, invalid tokens, missing IP
- Error response format (RFC 9457 structure)

`BaseRouteSpec` provides the Baklava DSL, kebs-baklava schema derivation for opaque types, shared bearer auth helpers (`bearer`, `bearerScheme`), and an RFC 9457 `Error[Unit]` schema.

`TestApplicationLoader` provides a full `ApplicationLoader` wired against Testcontainers PostgreSQL and Mailpit, with `FakeAuthVerifier` injected in place of real Firebase. It also provides `validJwt(authContext)` for generating valid JWTs in authenticated endpoint tests, and `jwtService` for decoding JWTs in assertions.

#### Baklava DSL patterns

**Unauthenticated endpoint:**
```scala
path("/v1/health-check")(
  supports(GET, description = "...", summary = "...", tags = Seq("Health Check"))(
    onRequest()
      .respondsWith[HealthCheckDto](Ok, description = "Application info")
      .assert { ctx =>
        val response = ctx.performRequest(allRoutes)
        response.body.name should not be empty
      }
  )
)
```

**Authenticated endpoint** — `securitySchemes` in `supports`, `security` in `onRequest`:
```scala
supports(GET, securitySchemes = Seq(bearerScheme), ...)(
  onRequest(security = bearer.apply(validJwt(TestData.authContext())))
    .respondsWith[List[RefreshTokenDto]](Ok, description = "List of sessions")
    .assert { ctx -> ctx.performRequest(allRoutes) }
)
```

**POST with request body:**
```scala
onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
  .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; a new user account was created")
  .assert { ctx ->
    val response = ctx.performRequest(allRoutes)
    response.body.jwt.toString should not be empty
    response.body.userCreated shouldBe true
  }
```

**Empty body (204 No Content)** — use `EmptyBody`:
```scala
.respondsWith[EmptyBody](NoContent, description = "Session revoked")
```

**Error responses** — use `Error[Unit]`:
```scala
.respondsWith[Error[Unit]](Unauthorized, description = "Invalid Firebase token")
```

**Seeding DB state** — when `ctx.performRequest` needs data to already exist (and the request inputs depend on it), use `withSetup`. The setup block runs at test-execution time, and its return value flows into both `.request { ... }` (to construct request inputs from seeded data) and `.assert { (ctx, s) => ... }`:
```scala
withSetup {
  val user   = TestData.user()
  val issued = TestData.issuedRefreshToken(userId = user.id)
  val _ = application.transactor
    .inTransaction {
      application.userRepository.create(user, Instant.now()) *>
        new RefreshTokenRepository().save(issued.token)
    }
    .unsafeRunSync()
  issued.secret
}.request { (secret: RefreshTokenSecret) =>
  onRequest(body = AuthWithRefreshTokenRequest(secret), headers = "127.0.0.1")
}.respondsWith[AuthenticatedResponse](Ok, description = "Authenticated with refresh token")
  .assert { case (ctx, _) =>
    val response = ctx.performRequest(allRoutes)
    response.body.jwt.toString should not be empty
  }
```

For path parameters from seeded entities, the same shape works:
```scala
withSetup {
  application.transactor
    .inSession(seedUser(seller) *> seedAuction(TestData.randomAuctionId(), seller.id))
    .unsafeRunSync()
}.request(auction => onRequest(pathParameters = auction.id))
  .respondsWith[AuctionDto](Ok, description = "Auction found")
  .assert { case (ctx, auction) =>
    val response = ctx.performRequest(allRoutes)
    response.body.id shouldBe auction.id
  }
```

If the request inputs are static and only the assertion needs the seeded value, ignore the setup result in the lambda:
```scala
withSetup {
  application.transactor.inSession(seedUser(seller).void).unsafeRunSync()
}.request(_ => onRequest(body = sampleCreateRequest(), security = bearer.apply(validJwt(sellerAuth))))
  .respondsWith[AuctionDto](Created, description = "Auction created")
  .assert { case (ctx, _) => ... }
```

**Multi-step setup** — when the setup involves several related entities, pack the whole chain into `withSetup` and return whatever the assertion needs. Prefer direct repository calls over HTTP self-calls — the setup is for test scaffolding, not for exercising the public API. Post-request cleanup (e.g., undoing a `blockedAt` modification so subsequent tests aren't poisoned) stays in `assert`, since it has to run after `performRequest`:
```scala
withSetup {
  seedFirebaseUser(blockedAt = Some(Instant.now())) // returns UserId after creating user + user_auth
}.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1"))
  .respondsWith[Error[Unit]](Locked, description = "User is blocked")
  .assert { case (ctx, userId) =>
    val response = ctx.performRequest(allRoutes)
    response.body.title shouldBe Some("User is blocked")

    // Unblock the user so subsequent tests aren't poisoned
    application.transactor
      .inTransaction { application.userRepository.update(userId, _.copy(blockedAt = None), Instant.now()) }
      .unsafeRunSync()
  }
```

#### Timing constraint

`onRequest(...)` arguments are evaluated at class construction time, before Testcontainers starts. Anything that depends on `application` (which needs the DB container) cannot be used in `onRequest` parameters directly. This is why:
- `validJwt` loads JWT config independently from `ConfigSource.default` rather than from `application.jwtConfig`
- Use `withSetup { ... }.request { seeded => onRequest(...) }` whenever request inputs are derived from seeded data — setup runs at test-execution time, after Testcontainers is up
- For requests with no seeded inputs, plain `onRequest(...)` outside `withSetup` is fine; just keep DB seeding inside `withSetup` (or `assert` for legacy specs)

#### Adding Decoder/Encoder to DTOs

Baklava deserializes response bodies and serializes request bodies. DTOs used in `respondsWith[T]` need a circe `Decoder`. DTOs used in `onRequest(body = ...)` need an `Encoder`. Add `derives Decoder` or `derives Encoder.AsObject` alongside the existing derivations:

```scala
final case class AuthenticatedResponse(jwt: InternalJwt, refreshToken: RefreshTokenSecret, userCreated: Boolean) derives Encoder.AsObject, Decoder
final case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwt) derives Decoder, Encoder.AsObject
```

### Layer 4: SMTP Tests

Real SMTP delivery via Mailpit container. Extend `AsyncWordSpec with AsyncIOSpec with Matchers with TestMailpit`.

**What belongs here:** verifying actual MIME structure — plain text, HTML, multipart/alternative, attachments, inline images. Use `getMessages` and `getMessage(id)` to inspect delivered mail via Mailpit's REST API.

Delivery timing (`at`/`in` parameters) is a scheduler concern, not SMTP. Test delay semantics in scheduler integration tests with a controllable clock.

## Shared Test Infrastructure

| File | Purpose |
|---|---|
| `TestTransactor` | Testcontainers PostgreSQL + Flyway + `withRollback`/`withSession` |
| `TestApplicationLoader` | Full `ApplicationLoader` with fake Firebase, for route tests |
| `BaseRouteSpec` | Baklava base trait with kebs schemas, auth helpers, error schema |
| `TestData` | Builders for domain objects with sensible defaults |
| `TestGivens` | `TestClock` (controllable time) and `TestUUIDGen` (deterministic UUIDs) |
| `TestMailpit` | Mailpit container with SMTP port and REST API helpers |
| `FakeAuthVerifier` | Test double for `ExternalAuthVerifier`, validates by token value |

## External Service Boundaries

External services are faked at the trait level. The production code depends on a trait; tests provide a fake implementation.

`ExternalAuthVerifier` is the boundary for Firebase. `AuthModule` exposes it as `protected lazy val externalAuthVerifier` which `TestApplicationLoader` overrides with `FakeAuthVerifier`. The fake accepts any token except `"invalid-token"`, which returns a failure.

When adding new external service integrations, follow the same pattern:
1. Extract a trait defining the service boundary
2. Have the production implementation implement the trait
3. Expose the binding as `protected lazy val` in the module trait
4. Override with a fake in `TestApplicationLoader`

## Baklava Output

Tests generate API documentation in three formats under `target/baklava/`:
- `openapi/openapi.yml` — OpenAPI 3.0.1 spec
- `simple/` — standalone HTML documentation
- `orpc/` — TypeScript oRPC contracts (npm-package-ready)
- `views/client/` — all three again without the `/admin/*` routes, for the clients to vendor

Generated by the `BaklavaSbtPlugin` configured in `build.sbt`. Run `sbt testFull` to regenerate (`testFull`, not `test` — see [Running Tests](#running-tests) below).

## Adding New Tests

**New domain logic** — add a unit test. No special infrastructure needed.

**New repository** — extend `TestTransactor`, use `withRollback` for each test case. Test the full CRUD lifecycle and any filter/query edge cases.

**New service** — if it's mostly orchestration over repositories, write an integration test with `TestTransactor`. If it has complex pure logic, extract and unit test that separately.

**New route** — extend `BaseRouteSpec with TestApplicationLoader`. Define all endpoints and response codes with the Baklava DSL so they appear in the generated API documentation. Use `tags` to group related endpoints in the OpenAPI output.

**New external service** — extract a trait, make the module binding overridable, provide a fake in `TestApplicationLoader`.

## Running Tests

```bash
sbt testFull                          # all tests, uncached (requires Docker)
sbt "testOnly *UserRepositorySpec"    # single suite
sbt "testOnly *RouterSpec"            # all route specs
sbt coverageAll                       # full run under coverage instrumentation + HTML report
```

Use `testFull`, not `test`, for a full run. Under sbt 2, `test` is incremental (testQuick-like) and caches its results in a global store (`~/.cache/sbt`); on a warm cache it can skip suites — or all of them — and still report success, and regenerate an empty OpenAPI spec. `testFull` always runs everything; `testOnly <pattern>` always runs its matches (input tasks aren't cached).
