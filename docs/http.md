# HTTP

The HTTP stack is http4s for the server and JSON entity codecs, [stir](https://github.com/theiterators/stir) for the routing DSL, and [baklava](https://github.com/theiterators/baklava) for OpenAPI generation. Routers extend `BaseRouter`, which mixes in everything you need to build a route.

## The stack

```
http4s          server (Ember), HTTP types, JSON entity codecs (via circe).
stir            akka-http-style routing DSL on top of http4s — directives,
                path matchers, marshalling.
baklava         OpenAPI / TypeScript-rest spec generation. Hooks into stir at
                test time and emits `target/baklava/openapi/openapi.yml`
                plus a Swagger UI served from /swagger in dev.
kebs            opaque-type / enum unmarshallers and matchers for stir, so
                `JavaUUID.as[AuctionId]` and `parameter("status".as[AuctionStatus])`
                just work.
```

You write routes the same way regardless of which library is doing what at any particular moment — stir hides http4s, baklava observes stir, kebs converts your domain types into stir matchers.

## Anatomy of a router

A router is a class extending `BaseRouter` (and any directive traits it needs, like `RateLimitDirectives`). Its constructor takes the services it calls and any cross-cutting context (event buses, rate limiter runtime, the current `TelemetryContext`).

```scala
class AuctionRouter(
  auctionService: AuctionService,
  eventBus: EventBus[AuctionEvent],
  rateLimiterRuntime: RateLimiterRuntime
)(using TelemetryContext) extends BaseRouter with RateLimitDirectives {

  val routes: Route = {
    (get & path("auctions") & pathEndOrSingleSlash) {
      complete {
        auctionService.listAuctions(...).map[ToResponseMarshallable] { views =>
          Ok -> views.map(AuctionDto(_))
        }
      }
    } ~
    (get & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) {
      auctionId =>
        complete { auctionService.getAuction(auctionId).map { … } }
    }
  }

  def authedRoutes(authContext: AuthContext): Route = { … }
}
```

The two methods are conventional, not enforced: `routes` for public routes (matched by `RouteProvider`), `authedRoutes(auth)` for routes that need an `AuthContext` (matched by `AuthRouteProvider`). A router can also expose `wsRoutes(wsb)` for WebSocket routes.

## What `BaseRouter` gives you

- **`Directives` and `WebSocketDirectives` from stir.** `path`, `get`, `post`, `entity(as[T])`, `parameters(...)`, `complete`, `~` (route concatenation), `&` (directive concatenation), `pathPrefix`, `pathEndOrSingleSlash`, etc. The vocabulary is akka-http's; if you've used that, you know stir.
- **`JsonProtocol`.** circe's `Encoder` / `Decoder` are in scope under those names, plus kebs derivations for opaque types and enums. `entity(as[CreateAuctionRequest])` decodes the body; returning an `(Status, Foo)` pair encodes the response. See [json.md](json.md) for the codec-derivation story.
- **kebs unmarshallers and matchers.** Path segments and query parameters can be typed: `JavaUUID.as[AuctionId]`, `Segment.as[VariantSpec]`, `parameter("status".as[AuctionStatus].?)`. The conversion is failed-as-rejection, so a malformed UUID returns 400 automatically.
- **`Status.*` exported.** `Ok`, `Created`, `NotFound`, `Forbidden`, etc. are accessible directly.
- **An `error(...)` helper** for problem-detail-shaped error responses (see below).

## Errors

`BaseRouter.error(status, typeTag, title, detail = None)` returns `IO[ToResponseMarshallable]` with a JSON body shaped like [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) problem-details, augmented with the current trace ID:

```json
{
  "type": "result:auction-not-found",
  "status": 404,
  "title": "Auction not found",
  "instance": "trace-id:7a1f8e6c…"
}
```

Service results are typically ADTs whose `Committed` / `NotFound` / `NotOwner` cases the route maps to status codes:

```scala
auctionImageService.commitUpload(...).map[ToResponseMarshallable] {
  case CommitUploadResult.Committed(image) => Created -> AuctionImageDto(image, apiPrefix)
  case CommitUploadResult.AuctionNotFound  => error(NotFound,  "auction-not-found", "Auction not found")
  case CommitUploadResult.NotOwner         => error(Forbidden, "not-owner",         "Only the seller can commit uploads")
  case CommitUploadResult.ObjectNotFound   => error(NotFound,  "object-not-found",  "Direct upload not found at the expected key")
  case CommitUploadResult.Conflict         => error(Conflict,  "image-id-conflict", "This image id is already in use")
}
```

Don't throw to signal a business outcome. Reserve exceptions for genuinely exceptional conditions (DB connection lost, JVM OOM, bug). See [error-handling.md](error-handling.md).

## Route assembly

`ApplicationLoader.routes(wsb)` assembles all module-contributed routes in one place:

```scala
def routes(wsb): Route =
  apiVersionPrefix {                                            // /v1 (auto-mounts /v2/* etc. when ApiVersion gains cases)
    authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
      route(auth) ~ route ~ wsRoutes(auth, wsb) ~ wsRoutes(wsb)  // authed + public, both HTTP and WS
    } ~
    // public-only fallback if auth rejects
    (route ~ wsRoutes(wsb))
  } ~
  adminRoutes ~                                                // /admin/*  (basic-auth gated)
  (if (env == Environment.Dev) mailPreviewRouter.routes ~ baklavaDocs)    // dev-only
```

Logging and tracing are layered on by middleware in `Main`, so individual routers don't have to call them.

## CORS

http4s' `CORS` middleware wraps the whole `HttpApp` in `Main` (so preflight `OPTIONS` is answered before routing). The `cors` config block:

```hocon
cors {
  enabled = true                     // CORS_ENABLED=false drops the middleware entirely
  allowed-origins = ""               // comma-separated origins; "*" = any; empty = derive (below) — CORS_ALLOWED_ORIGINS
  max-age = 1h                       // how long browsers may cache the preflight — CORS_MAX_AGE
}
```

`allowed-origins` resolution (in `Cors.policy`):

- an explicit list (`https://app.example.com,https://www.example.com`) → exactly those origins
- `*` → any origin
- empty (the default) → **any origin in dev** (`app.environment = "dev"`), otherwise **the host of `http.base-url`** — a safe fallback that's zero-config when the SPA and the API share an origin behind one proxy, and never silently `*` in production

Methods and request headers are allowed wildcard — what a typical SPA wants; lock them down in `Cors.policy` if you need to.

**No credentials.** `Access-Control-Allow-Credentials` is not set: auth here is a client-set `Authorization: Bearer <jwt>`, which doesn't need it. If you add cookie-based auth, turn credentials on in `Cors.policy` **and** switch `allowed-origins` to a specific list — a `*` origin with credentials is rejected by browsers.

**Production:** your frontend is usually a different origin from the API, so set `CORS_ALLOWED_ORIGINS` to your real frontend origin(s). The `base-url` fallback only covers the same-origin case.

## OpenAPI and Swagger UI

baklava generates the OpenAPI spec by observing stir routes during the test suite. Each `RouterSpec` is a baklava DSL spec that describes the routes' inputs, outputs, and example bodies; running the test suite produces:

- `target/baklava/openapi/openapi.yml` — the OpenAPI spec
- `target/baklava/swagger-ui/` — a static Swagger UI bundle pointing at the spec
- `target/baklava/orpc/` — a TypeScript [oRPC](https://orpc.dev) contract package
- `target/baklava/views/client/` — the same three outputs without the `/admin/*` routes; its `orpc/` is what the [reference frontend](frontend.md) and [mobile app](mobile.md) vendor, so the admin API's shape stays out of every shipped client bundle. The view is one entry in `baklavaGenerateConfigs` (`"views"` in `build.sbt`); add more views there if a public subset of the API needs its own document.

In dev (`app.environment = "dev"` in config), the app serves the same artifacts at:

- `/openapi/openapi.yml`
- `/swagger` — the Swagger UI
- `/docs` — alias

If the dev URLs return empty, run `sbt testFull` once to generate the artifacts; they live in `target/`, not in git. From then on every test run keeps them current — `test` and `testOnly` replace the entries of the suites that ran and keep the rest (see [dev-workflow.md](dev-workflow.md)).

The contract is only as complete as the specs: an error code that a router can answer with but no spec case produces never reaches the OpenAPI spec or the generated clients, which switch exhaustively on the declared codes. `sbt Test/verifyErrorCodes` (part of `verifyAll` and CI, run after `testFull`) compares every `error(<status>, "<code>", …)` call in `src/main` with the `result:<code>` types in the generated spec and fails on any code the specs never exercise. When you add an `error(...)` branch, add a spec case that reaches it.

## Admin endpoints

Everything under `/admin` is gated by HTTP Basic auth using credentials from the `admin.user` / `admin.password` config (set via env vars in production). Out of the box:

- `/admin/health-check` — deeper probe than the public `/v1/health-check`; checks Postgres + SMTP from `HealthCheckModule`.
- `/admin/jobs` — read-only scheduler dashboard listing registered tasks and queued/running rows; provided by `SchedulerAdminRouter`.

Admin is not a module — `ApplicationLoader` gates the whole subtree once and modules contribute via the same `route` method (the `/admin` routes live on `ApplicationLoader` itself for now, since they're cross-cutting).

## Rate limiting

Routers that need request-rate caps mix in `RateLimitDirectives` and use `rateLimited(name, to, within, by = …)` around a `complete`. The `name` is the bucket key; `to` is the burst, `within` the window; `by` keys per-user (`_ => "user:..."`) or per-IP (default). Implementation lives in `RateLimiterRuntime` and is in-memory by default — see [rate-limiting.md](rate-limiting.md) to swap it for Redis.

## Pagination

List endpoints are offset-paginated. The pieces live in `madrileno.utils.pagination` (layer-neutral — the DB DSL and the service both lean on them, the router maps to DTOs): `Limit` / `Offset` (validated opaque types), `SortDirection` (`Asc` / `Desc`), `PageRequest[F]` (limit + offset + a `sortBy: F` field-enum + direction), and the response envelope `Page[A] { items, total, limit, offset }` — a plain data type, kept codec-free; its circe `Encoder`/`Decoder` are generic `given`s on `BaseRouter`.

`GET /v1/auctions` is the worked example: `?limit=` (1–100, default 20, out-of-range values are clamped — not rejected), `?offset=` (default 0), `?sort-by=` (a per-endpoint enum — `CreatedAt | EndsAt | StartingPrice` here, default `CreatedAt`), `?sort-dir=` (default `Desc`). An unknown `sort-by`/`sort-dir` value is a 400 (kebs derives the param codec from the enum). The `BaseRouter` directive `paginated(defaultSort)` extracts those four params (clamping `limit`/`offset`) and hands the route a `PageRequest[F]` — adding pagination to another endpoint is one `& paginated(...)` in the route. The repository appends the primary key (same direction) as a tie-break to whatever sort the client picked — without it, paging silently skips or duplicates rows when the sort keys tie. The total is one extra `COUNT(*)` with the same `WHERE`; the client derives `totalPages` / `hasMore` from `total`, `limit`, `offset`.

On the repository side, `madrileno.utils.db.dsl` carries the pieces. A row filter that mixes in `PageableSqlFilter[F]` holds an `Option[PageRequest[F]]` field and implements three things: `pageRequest` (that field), `sortColumnFor(field: F): Column[?]` (that endpoint's sort-enum → `Column` mapping), and `tieBreakColumn` (the primary key). `PageableSqlFilter` turns those into both the `ORDER BY <col> <dir>, <pk> <dir>` (`orderByFragment`) and the `OFFSET ? LIMIT ?` (`offsetLimitFragment`). So `repository.findPageByFilter(filter)` returns `(rows, total)` for a plain `SELECT … WHERE … ORDER BY … OFFSET … LIMIT` (+ a sibling `COUNT(*)`) — a new paginated list endpoint needs no hand-rolled SQL at all. The auctions endpoint is the exception: `AuctionRepository.list` hand-writes its `SELECT` because of the `currentPrice` `LEFT JOIN LATERAL`, but it still just reads `filter.orderByFragment` / `filter.offsetLimitFragment` and calls `repository.countByFilter(filter)` — no ad-hoc ordering/paging SQL. (A filter with the extra `page` field can't use the `SqlFilterDerivation.filterFragment(this, cols)` macro — that needs a `Mirror.ProductOf` over exactly the predicate fields — so it writes `filterFragment` via `fromPredicates((pred -> col, …))`, the lower-level primitive that's already in `SqlFilter`.)

**Cursor pagination — the bid feed.** Offset is right for a catalogue ("jump to page 4") but weak for a high-write feed: deep offsets are O(offset), and concurrent inserts shift rows between pages. `GET /v1/auctions/{auctionId}/bids` is the worked keyset example. The envelope is `Cursor[A] { items, hasMore }` — no `total` (the whole point of cursor is to skip the count), a separate type from `Page[A]` so neither constrains the other, and codec-free with its circe `given`s on `BaseRouter` like `Page`. The request side is `CursorRequest[K] { limit, after }` (reuses `Limit`, the same 1–100 opaque type). For a general sort you keyset on `(sortColumn, primaryKey)` and expose both as raw query params via the `cursorPaginated(afterSortParam, afterIdParam)` directive (supplied together or not at all — a half cursor is a 400). The `after` is the bare keyset value, not an opaque blob — simpler, at the cost of pinning the field name into the contract. The bid feed is a simpler, single-key case of this — see below.

On the DSL side a row filter mixes in `KeysetSqlFilter[S, I]` and supplies `keysetCursor`, `keysetColumns` (the `(sortColumn, pkColumn)` pair), `baseFilterFragment` (its own predicates), and optionally `keysetDirection` (defaults `SortDirection.Desc` / newest-first). The trait ANDs `WHERE (sortColumn, pkColumn) < (?, ?)` onto the filter (parenthesizing the base), orders by `(sortColumn, pkColumn) DESC`, and fetches `limit + 1` rows; `FilteringRepository.findCursorPageByFilter(filter)` returns `take(limit)` as the page plus a `hasMore` flag — the extra row is only the probe for `hasMore`, never returned and never a `COUNT(*)`, and the client's next `after` is the last *returned* item. To expose cursor pagination on an endpoint: pick a feed, build a `KeysetSqlFilter`-mixing row filter (with a composite index on the two keyset columns, or the keyset `WHERE` is just a slow scan), make sure its item DTO exposes both keyset columns — the client reconstructs the next `?after-…` from the last item, so without them the endpoint is non-navigable — decode the `?after-…` params into the keyset value, return `Cursor[Dto]`. For the common case where the primary key is itself the chronological key (a UUIDv7 id), there's no sort column and no filter mixin: `FilteringRepository.findCursorPageByKey(baseFilter, keyColumn, cursor)` keysets on that one column (`WHERE (base) AND key < ? ORDER BY key DESC LIMIT n+1`, parenthesizing the base so an `OR` filter can't bind loosely), reusing the table's ordinary filter for the `WHERE`. The bid feed is exactly that — the router parses the cursor with `cursorPaginatedByKey("after-id")` (the single-key sibling of `cursorPaginated`, clamping `?limit` the same lenient way), and `BidRepository.pageByAuction` calls `findCursorPageByKey(BidRowFilter(auctionId = …), BidRowTable.id, cursor)`, newest-first by the v7 id (index `bid (auction_id, id)`); `BidHistoryEntryDto` exposes `id` so the client builds the next `?after-id`. Its `bidderRef` (the per-auction pseudonym) is `BidderRef.forBidder(secret, auctionId, bidderId)` — a keyed `HMAC-SHA256` truncated to 64 bits, computed in the service and stable per `(auction, bidder)`, so every bid from the same bidder shows the same pseudonym. The key matters: a plain hash of the (public) auction and user ids would let anyone recompute a known user's token and check whether they bid, so the HMAC key is what keeps the pseudonym un-forgeable. The showcase hardcodes that key in `AuctionService`, so it's only as secret as the source — a real deployment must inject a deployment-specific secret (env/config). It's an opaque token, not a sequential count, and id-ordering leans on the v7 invariant (enforced by `IdGenerator` + the scalafix `UUID.randomUUID` ban). Ordering is newest-first to millisecond precision — two bids in the same millisecond sort by id (stable across pages, but not strictly by wall-clock).

## Testing routes

Two flavours:

- **Service-level specs** (e.g. `AuctionImageServiceSpec`) test the IO logic directly with a real Postgres + in-memory `ObjectStore`, no HTTP layer. Fast, no marshalling concerns.
- **Router specs** use baklava's DSL to describe and test the route at the HTTP entrypoint. These are also what produces the OpenAPI spec, so writing a router spec is also writing the public API documentation. See [testing-guide.md](testing-guide.md).

## Where to look next

- [json.md](json.md) — codec derivation, opaque-type and enum codecs.
- [auth.md](auth.md) — how `AuthContext` arrives at `authedRoutes`.
- [error-handling.md](error-handling.md) — Result-ADT-per-method convention, status mapping.
- [rate-limiting.md](rate-limiting.md) — `RateLimitDirectives` mechanics.
- [websockets.md](websockets.md) — `wsRoutes` and the WebSocket builder.
