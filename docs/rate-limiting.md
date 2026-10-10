# Rate limiting

Per-route rate limiting as a stir directive. Wrap a route in `rateLimited(...)` and you get a fixed-window counter scoped to a discriminator key (client IP by default, anything you want as an opt-in). Over the limit, the request gets `429 Too Many Requests` with a `Retry-After` header; under it, the route runs normally.

## The shape

```scala
trait RateLimiter {
  def increment(key: String, window: FiniteDuration): IO[Long]
}

trait RateLimiterRuntime {
  def rateLimiter: RateLimiter
  def trustedProxies: List[Cidr[IpAddress]] = Nil
}
```

`increment(key, window)` is the whole API: bump the counter for `key` in the current `window`, return the new count. Returning `Long` (not `Boolean` or `Either`) keeps the policy in the caller — the directive layer decides what counts as "over" by comparing to the configured limit. Different routes might want soft warnings, hard 429s, or instrumentation around the same counter; the trait stays out of that decision.

`RateLimiterRuntime` is the factory layer, mirroring `CacheRuntime` / `EventBusRuntime` / `ObjectStoreRuntime` / `CircuitBreakerRuntime`. One runtime per app, swapped in `Main`. It also carries `trustedProxies` — the proxy ranges allowed to set `X-Forwarded-For` — because client-IP resolution is a property of the deployment, not of any single route. See [discriminator keys](#discriminator-keys).

## The `rateLimited` directive

`RateLimitDirectives` mixes the directive into any router that needs it:

```scala
class AuctionRouter(..., override protected val rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter with RateLimitDirectives {

  val routes: Route =
    (get & path("auctions") & pathEndOrSingleSlash) {
      rateLimited("auctions.list", to = 60, within = 1.minute) {
        complete { … }
      }
    }
}
```

The trait's only requirement is the `rateLimiterRuntime` — satisfy it with the constructor parameter the router already takes (`override protected val`). The directive derives both the counter and the trusted-proxy list from it internally, so there's nothing else to wire and `trustedProxies` is never exposed for a router to get wrong.

Inside the directive:

```scala
val key = s"$name:${by(req)}"
rateLimiter.increment(key, within).flatMap { count =>
  if (count > to) /* 429 with Retry-After */
  else            /* run inner route */
}
```

Three things to know:

- **The bucket key is `name:discriminator`.** `name` separates routes — `auctions.list` and `auctions.create` have independent limits. `discriminator` (from `by`) separates clients within the same route. Together they're "this client, on this route, in this window."
- **The counter only increments on requests that reach the directive.** Stir's directive composition means `(post & path(...))` short-circuits on a `GET` before the inner directive runs. So a 429 cap on `POST /hello` doesn't burn through quota when traffic is all `GET /hello`. The spec covers this explicitly.
- **`Retry-After` is the window length, not "time until the bucket resets to zero."** The fixed-window algorithm doesn't track per-key resets; the value tells the client how long the window is. Close enough in practice; perfect for clients that just want a "back off this long" hint.

## Discriminator keys

Three out-of-the-box extractors, plus your own:

| Function                       | What it returns                                                                            | When to use                                           |
| ------------------------------ | ------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| `byClientIpForwarded` (default) | The client IP, resolving `X-Forwarded-For` **only through configured trusted proxies**; otherwise the socket IP. | The default. Safe whether or not you sit behind a proxy. |
| `byClientIp`                   | The socket-level remote IP. Always ignores `X-Forwarded-For`.                              | When you explicitly never want to honor forwarded headers. |
| `byHeader(name)`               | The value of an arbitrary header, or `"unknown"`.                                          | Internal-only paths keyed on an internal client ID.   |
| Custom `Request[IO] => String` | Anything you can compute from the request. Most usefully, the authenticated user.          | Per-user limits inside an authed route.               |

### Why the default is trusted-proxy aware

`X-Forwarded-For` is client-controlled. If you key on it blindly, anyone can rotate the header to mint a fresh bucket per request and the limit is useless. But ignoring it entirely is also wrong: behind a CDN or load balancer the socket IP is the proxy's, so *every* client collapses into one bucket. Neither naive option is safe, so `byClientIpForwarded` trusts the header only as far as the request actually arrived over proxies you've declared:

- It honors `X-Forwarded-For` **only when the direct peer (socket address) is a configured trusted proxy.** A directly-reachable app ignores the header completely — a forged `X-Forwarded-For` from an arbitrary client can't move the bucket.
- It then walks the forwarded chain from the nearest hop, **skips further trusted proxies, and takes the first untrusted address** as the client. Parsing from the right (not the leftmost entry) is what makes it correct behind proxies that *append* rather than overwrite — a client can prepend forged entries, but they sit to the left of the address the trusted proxy honestly recorded, so they're discarded.
- With **no trusted proxies configured it is identical to `byClientIp`** (socket IP), so the default is safe out of the box.

Configure trusted proxies in `application.conf` (or `RATE_LIMIT_TRUSTED_PROXIES`):

```hocon
rate-limit {
  trusted-proxies = ""  # comma-separated CIDRs/IPs, e.g. "10.0.0.0/8,192.168.0.0/16"
  trusted-proxies = ${?RATE_LIMIT_TRUSTED_PROXIES}
}
```

**Deployment rule of thumb:** behind nginx / Cloudflare / an ELB, set this to the proxy's egress ranges — otherwise all clients share one bucket. On bare TCP exposure, leave it empty. If you only ever want the socket IP regardless of deployment, pass `by = byClientIp` explicitly.

For per-user limits inside `authedRoutes`, build the extractor from the auth context:

```scala
def authedRoutes(authContext: AuthContext): Route = {
  val byUser: Request[IO] => String = _ => s"user:${authContext.userId}"
  rateLimited("auctions.create", to = 10, within = 1.minute, by = byUser) {
    …
  }
}
```

The `s"user:..."` prefix isn't strictly required — `byHeader` returns raw values too — but it makes log lines and any cross-key collisions self-describing. Pick a naming convention and stick with it (`user:...`, `ip:...`, `apikey:...`).

## What the algorithm is and isn't

`RateLimiterRuntime.scaffeine` builds one Caffeine cache per `window` size, keyed on `String → AtomicLong`. The cache's `expireAfterWrite(window)` does double duty: it caps memory, and it expires the counter exactly one window after the first hit for a key. Subsequent calls with the same key inside the window increment the same `AtomicLong`; once it expires, the next call gets a fresh `AtomicLong(0)`.

Implications:

- **Fixed window, not sliding.** A client that spends its quota at the end of one window and the start of the next can do up to `2 × to` requests in `within` seconds. Good enough for blocking accidental loops and DOS-y bots; not good enough for billing-grade enforcement.
- **No global queue.** Requests aren't queued; they're rejected. If you want backpressure (slow down everyone instead of failing some), add it at a different layer.
- **No burst smoothing.** A client can spend the full window's quota in the first 10ms. If that's a problem (it usually isn't for HTTP APIs at this scale), reach for a token-bucket implementation instead.
- **Stronger algorithms on the trait.** If you need sliding-window or token-bucket, add them as new methods on `RateLimiter` — and keep `increment` for the simple case.

## Test runtime

`TestRateLimiterRuntime.unbounded` is what `TestApplicationLoader` wires:

```scala
val unbounded: RateLimiterRuntime = new RateLimiterRuntime {
  override val rateLimiter: RateLimiter =
    (_: String, _: FiniteDuration) => IO.pure(0L)
}
```

`increment` always returns `0` — well below any limit, so no test ever gets a `429` from rate limiting it didn't ask for. This is the right default; assert behaviour against the route's actual logic, not its rate-limiter friction.

For tests that *want* to verify rate limiting, wire the production runtime explicitly. `RateLimitDirectivesSpec` mixes the directive into a bare router with a real Caffeine-backed runtime, varying `trustedProxies` to cover the forwarded-IP paths:

```scala
new BaseRouter with RateLimitDirectives {
  override protected val rateLimiterRuntime: RateLimiterRuntime =
    RateLimiterRuntime.scaffeine(trustedProxies = List(Cidr.fromString("10.0.0.0/8").get))
}
```

`AuthRateLimitSpec` and `AdminRateLimitSpec` go a level up: they drive the real routers with a bounded runtime and assert the `429` actually fires — auth through the full app (`TestApplicationLoader` exposes an overridable `rateLimiterRuntime`), admin by constructing the router directly. That's deliberate: with the inert default limiter, deleting a `rateLimited(...)` call would otherwise pass every router spec silently. Each rate-limited auth and admin endpoint therefore gets a focused test that fails if its limit disappears — including that `admin.heapdump`'s global key is shared across distinct clients. `AuthRateLimitSpec` also documents the `429` `Error` on `POST /v1/auth/firebase`, which is how `rate-limited` enters the generated contract that `Test/verifyErrorCodes` checks. (The auction module's limits predate these specs and lean on its own router spec.)

## Multi-instance considerations

Same caveat as the cache: each JVM has its own counters. With `N` instances behind a load balancer, the effective limit is roughly `N × to` per window (assuming round-robin). For a hard global cap, swap the runtime to a shared store — Redis with `INCR` + `EXPIRE` is the canonical pattern. Add `RateLimiterRuntime.redis(...)` when you need it; the trait is the swap point.

In practice, per-JVM is usually fine when:

- The limit is "abuse prevention," not "billing enforcement." Casual bots get blocked even at `N × to`; the goal is to keep the server upright, not to enforce a contractual ceiling.
- Sticky sessions or consistent hashing route a given client to the same instance. The local counter is then effectively per-client.

Don't reach for distributed rate limiting until you've confirmed the per-JVM version isn't enough.

## What the response looks like

Limit exceeded:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json

{
  "type": "result:rate-limited",
  "status": 429,
  "title": "Too many requests; retry after 60s",
  …
}
```

The body uses the same RFC 9457 error envelope as every other error. The `type` is `result:rate-limited`; clients can dispatch on it without reading the title.

## Sizing limits

Three signals to use when picking `to`:

- **Steady-state expected traffic.** A list endpoint that an authenticated user hits once per page-load probably tolerates 60/min comfortably; one a poller hits every second needs higher (or a websocket).
- **Cost per request.** A read of a single row can be liberal. A write that triggers a fan-out, an external API call, or media processing should be tighter.
- **Worst-case parallelism.** If a malicious client opens 10 sockets and spams, you want `to` low enough that `10 × to / window` doesn't melt the backend.

The auction module's defaults illustrate the spread:

```
auctions.list           60/min   (read, by IP)
auctions.get           120/min   (read, by IP)
auctions.bids          120/min   (read, by IP)
auctions.create         10/min   (write, by user)
auctions.cancel         30/min   (write, by user)
auctions.bid            30/min   (write, by user)
```

Reads are per-IP and generous; writes are per-user and tighter. That's a reasonable default starting point. Tune from real traffic, not from imagination.

The auth and admin endpoints show the other two axes — pre-auth abuse surface and expensive operations:

```
auth.firebase / oidc / dev  10/min   (pre-auth, by client IP)
auth.refresh                30/min   (pre-auth, by client IP)
admin.threaddump            20/min   (by client IP)
admin.heapdump               3/5min  (global key — protects a shared resource, the disk)
```

The auth limits are per-IP because they're public, pre-authentication endpoints — the brute-force / credential-stuffing surface. `admin.heapdump` uses a constant `by = _ => "global"` key instead of per-client: a heap dump writes a large file to disk, so the resource at risk is the box itself, and the cap must hold across *all* callers, not per-admin. When the danger is "this operation is expensive for the server," a global key is the right tool; when it's "this client is being abusive," a per-client key is.

## Naming the bucket

`name` should identify the route in a way that's stable across refactors. Conventions:

- `module.action` — `auctions.list`, `users.create`. Short, grep-able, survives URL changes.
- One name per route handler. Don't share `name` between two routes — they'll drain each other's quota.
- Don't put the discriminator in `name`. The discriminator goes in `by`. `name = "auctions.create.user-123"` defeats the discriminator and burns memory on per-user cache entries.

## Observability

The directive doesn't ship metrics or spans of its own. To know how often rate limiting fires, add a counter at the call site or extend `RateLimitDirectives` to record an OTel counter on the over-limit branch — both fine. The 429 responses already show up in the http4s server metrics by status code, so you can dashboard "429 rate per route" without code changes.

## Where to look next

- [http.md](http.md) — the routing layer the directive plugs into.
- [auth.md](auth.md) — `AuthContext` is what you key per-user limits on.
- [observability.md](observability.md) — adding counters around the directive; reading 429 rates from the existing metrics.
- [cache.md](cache.md) — same runtime pattern; the rate limiter is implemented on top of Caffeine the same way the cache is.
