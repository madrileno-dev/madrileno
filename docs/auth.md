# Authentication

The auth model is a thin layer of conventions on top of OAuth-flavoured external identity providers. Firebase, any number of generic OIDC OPs (Zitadel, Auth0, Keycloak, …), and a dev-only email-shortcut provider all sit behind a single `ExternalAuthVerifier` trait, multiplexed by an `AuthVerifiers` map keyed by `Provider`. The `Authenticated` JWT and refresh-token plumbing downstream is provider-agnostic.

The flow:

```
                               ┌───────────────┐
client logs in with Firebase   │   Firebase    │
─────────────────────────────► │  client SDK   │
                               └───────┬───────┘
                                       │ Firebase JWT
                                       ▼
client → POST /v1/auth/firebase {firebaseJwtToken}
                                       │
                                       │ verify Firebase JWT
                                       ▼
                          ┌──────────────────────────┐
                          │ ExternalAuthVerifier     │
                          │   = FirebaseService      │  → returns
                          └──────────────────────────┘    VerifiedExternalToken
                                       │
                                       ▼
                          ┌──────────────────────────┐
                          │ AuthenticationService    │  upserts User,
                          │                          │  records UserAuth,
                          │                          │  mints internal JWT,
                          │                          │  issues RefreshToken
                          └──────────────────────────┘
                                       │
                                       ▼
client ◄─── 200 {jwt, refreshToken}

later requests:
client → Authorization: Bearer <internal jwt>
                                       │
                                       ▼
                          ┌──────────────────────────┐
                          │ UserAuthenticator        │
                          │   decodes the JWT,       │
                          │   produces AuthContext   │
                          └──────────────────────────┘
                                       │
                                       ▼
                          AuthRouteProvider.route(auth)
```

External identity is verified once; from then on the app trusts its own short-lived JWT and a longer-lived refresh token.

## The pieces

| File                              | What it does                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `ExternalAuthVerifier`            | The trait. One method: `verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]]`.            |
| `AuthVerifiers`                   | `Map[Provider, ExternalAuthVerifier]`. Built in `AuthModule` from config; `authenticateWithProvider` dispatches through it. |
| `Rs256TokenVerifier`              | Shared RS256-JWT core. Verifies signature via a `kid → RSAPublicKey` resolver, then `iss` / `aud` / `exp` / `nbf` with 30 s leeway. |
| `FirebaseService`                 | Wraps `Rs256TokenVerifier` for Firebase: issuer derived from `FIREBASE_PROJECT_ID`, keys fetched via `FirebaseKeyProvider` (Google's x509 endpoint). Enabled when the project id is set. |
| `JwksProvider` + `OidcDiscovery`  | Used by the generic OIDC path: fetch a JWKS (cached, key-rotation-aware) and discover `jwks_uri` from `<issuer>/.well-known/openid-configuration` if not configured. |
| `DevAuthVerifier`                 | Treats an email-shaped token as a verified identity; reached via `POST /v1/auth/dev`. Registered only when `dev-auth.enabled = true` (`DEV_AUTH_ENABLED`); the route is always wired but returns 404 `unknown-provider` when the verifier isn't registered. |
| `JwtService`                      | Encodes / decodes the app's own JWT (`InternalJwt`). HS256 (`com.auth0:java-jwt`). Signs an `AuthContext`.    |
| `AuthenticationService`           | `authenticateWithProvider(provider, cmd)`: verify external → upsert `User` + `UserAuth` → mint internal JWT + refresh token. |
| `AuthRouter`                      | `POST /v1/auth/firebase`, `POST /v1/auth/oidc/{provider}`, `POST /v1/auth/dev` (dev only), `POST /v1/auth/refresh-token`, `POST /v1/auth/logout` (all answer with `Cache-Control: no-store`), `GET/DELETE /v1/auth/sessions`. |
| `UserAuthenticator`               | The function passed to `authenticateOrRejectWithChallenge`. Decodes the internal JWT, returns `AuthContext`.  |
| `RefreshTokenRepository`          | Persists refresh tokens (secret hash only, never the secret in the clear; a used row also carries its successor's secret sealed under a key only the old secret derives); lookup by secret hash under the family lock, live-token listing by user, user agent, or family, family revocation. |
| `cleanupExpiredRefreshTokensTask` | Recurring task that deletes rows 60 days after their `expires_at` (tombstone GC); used rows stay as replay evidence until then. |

## `AuthContext`

The data the rest of the app sees once a request is authenticated:

```scala
final case class AuthContext(
  userId: UserId,
  fullName: Option[FullName],
  avatarUrl: Option[URI],
  emailVerified: Boolean
)
```

It's the JWT payload, minted at login and decoded on every authenticated request. The default fields are deliberately small — just enough for routes to know who's calling without another DB lookup.

Add fields when your routes consistently need them. Tenant id, role, account-tier, feature-flag bundle — anything you'd otherwise have to look up on every request — fits well here. Update the case class, the `AuthenticationService` mint sites, and `AuthContext.given Encoder/Decoder` (kebs derives them). Existing routes need no changes; they just have more on the `auth` parameter.

Two caveats. First, every field added grows every JWT — fine for stable identity-shaped data, wasteful for things that churn (last-seen-at, online-status). Second, the JWT is signed but not encrypted; don't put anything there you wouldn't be comfortable putting in a base64-encoded string the client can decode. For data that changes often or shouldn't be visible to the client, do a per-request DB lookup instead.

`AuthRouteProvider.route(auth: AuthContext)` is how a module receives the authenticated context. From inside a router:

```scala
def authedRoutes(authContext: AuthContext): Route = {
  (post & path("auctions") & entity(as[CreateAuctionRequest])) { request =>
    complete {
      auctionService.createAuction(authContext.userId, request)…
    }
  }
}
```

## The login flow

1. **Client obtains a Firebase ID token** using a Firebase client SDK (browser, mobile, whatever Firebase supports). The app doesn't see passwords or social-login flows — those are Firebase's job.

2. **Client `POST /v1/auth/firebase`** with the Firebase JWT. The route extracts the user-agent and IP, then calls `AuthenticationService.authenticateWithProvider(Provider.Firebase, …)`.

3. **Server verifies the Firebase JWT** through `ExternalAuthVerifier.verifyToken`. `FirebaseService` validates the RS256 signature against Google's published certificates (fetched once, cached per the `Cache-Control: max-age` Google returns — usually 6 h), checks `iss` / `aud` / `exp`, and returns a `VerifiedExternalToken` carrying the Firebase user-id, email, name, and photo URL.

4. **Server upserts the User and UserAuth.** `User` is the application's user record; `UserAuth` records the link to a Firebase identity. First-time logins create both; returning users update them with whatever Firebase reported (e.g. updated avatar).

5. **Server mints the internal JWT and a refresh token.** The JWT signs an `AuthContext` with `jwt.secret`, valid for `jwt.valid-for` (default 5 minutes). The refresh token is a 256-bit random secret (base64url, 43 chars) that the client receives exactly once; the server stores only its SHA-256 in `refresh_token.secret_hash`, alongside a row `id`, a `family_id` and `family_created_at` shared by every rotation of the same login, user-agent, IP, created-at, used-at, and `expires_at` (`refresh-token.valid-for`, default 90 days, `REFRESH_TOKEN_VALID_FOR`). A **session** is a family: `GET /v1/auth/sessions` lists one entry per family with a live token, keyed by the family id, and `DELETE /v1/auth/sessions/{id}` revokes that family. Neither the family id nor the row id is a credential, so holding a JWT never yields a refresh token. Token-bearing responses carry `Cache-Control: no-store` (RFC 6749 §5.1).

6. **Result:** `200 { jwt, refreshToken, userCreated }`. `userCreated` is `true` when this call provisioned a new `User` account (first-time login), `false` for a returning user — clients use it to branch their UX (show onboarding vs. just log in). The status code is `200` either way; the "was a user created" signal lives in the body so a typed client (oRPC, OpenAPI codegen) sees one response shape, not two keyed on status. Subsequent requests carry `Authorization: Bearer <jwt>`.

## Refreshing

The internal JWT is short-lived. When it expires, the client `POST /v1/auth/refresh-token` with the refresh-token secret. `AuthenticationService.authenticateWithRefreshToken` hashes it, looks up the row by hash, verifies it hasn't been used, revoked, or expired, marks it `used`, and issues a fresh JWT + a fresh refresh token in the same family. The shape of the value is not validated: anything that matches no stored hash, an old UUID or garbage included, is answered 401 `invalid-token`, so clients have a single signal to re-login on and 400 is reserved for malformed JSON.

Refresh tokens are one-time-use and every rotation inherits the family of the token it replaced. Replaying a token that was already used is treated as evidence of theft: the whole family is revoked (OAuth 2.0 Security BCP "refresh token rotation with reuse detection"), so whichever of the attacker or the legitimate client refreshes second kills the chain for both, and the user logs in again. Other devices' families are untouched. A token that is merely expired or already revoked is rejected without side effects.

One replay is not theft: the retry of a client that never received the rotation response. A phone in a tunnel, a network switch, iOS suspending the app mid-request: the request reached the server and consumed the token, the client still holds the old one, and its next refresh would count as a replay and log the user out. `refresh-token.reuse-grace` (`REFRESH_TOKEN_REUSE_GRACE`, default 30 s) covers it: within that window of the original use, a replay of a used token is answered with the same successor the rotation minted, plus a fresh JWT, as long as that successor is still unused. The server does this without keeping a secret in the clear. At rotation it seals the successor's secret with AES-GCM under a key derived by HMAC-SHA256 from the presented secret and stores the ciphertext in `successor_secret` on the used row. Only a holder of the old secret, i.e. the client that just sent it, can open the box; the stored hash does not derive the key, so a database leak does not either. The window weakens reuse detection by exactly its length. A thief replaying inside it gets the same successor the victim holds, and the theft surfaces only when one of the two presents a token more than `reuse-grace` after the other used it; a thief who keeps replaying within the window after every rotation of the victim rides along undetected for as long as that lasts. That is the trade-off for not logging phones out on every lost response: keep the window short, and rely on `max-family-age` (below) to bound the ride, since it applies to a re-delivery as much as to a rotation. A replay whose successor was already used, or that lands after the window, revokes the family as above. Set the window to zero to disable re-delivery; then every replay revokes and detection is strict again.

Rotation and replay detection on the same family are serialized with a transaction-scoped Postgres advisory lock keyed by `family_id`, taken before any row lock (`RefreshTokenRepository.findAndLockFamilyBySecretHash`: look the token up without a lock, lock its family, then re-select it `FOR UPDATE`). Row locks alone are not enough: a replay's revoking `UPDATE` would wait for an in-flight rotation to commit but, under read-committed snapshots, never see the successor that rotation inserted — the successor would survive the revocation. Taking the family lock first also fixes the lock order, so a replay holding the family lock can never wait on a row the rotation holds while the rotation waits on the family lock.

Each token also expires `refresh-token.valid-for` after it was minted (default 90 days). Because rotation mints a fresh token with a fresh window, this behaves as an inactivity timeout. On top of it, `refresh-token.max-family-age` (default 365 days, `REFRESH_TOKEN_MAX_FAMILY_AGE`) is the absolute lifetime: once a family is that old, the next refresh revokes it and answers 401 regardless of activity, so a session cannot live forever on refreshes alone.

Revocation is family-wide and takes the same lock. `DELETE /v1/auth/sessions/{id}` locks the family, checks it belongs to the caller, and soft-deletes every token in it; `DELETE /v1/auth/sessions?user-agent=…` does the same for every family whose live token carries that user agent, locking the families in a fixed order. Revoking only the listed row would race a concurrent rotation the same way replay detection did: the row re-reads as used, nothing happens, and the freshly minted successor keeps the device logged in.

`cleanupExpiredRefreshTokensTask` runs daily at 1 AM to delete rows whose `expires_at` is more than 60 days in the past, whether they were used, revoked, or never presented. Used rows are deliberately kept for their whole validity window: they are the evidence that lets a victim's late replay reveal a hijacked family. Deleting them sooner would turn that replay into "not found" and leave the thief's chain running. Retention is bounded by `valid-for` + 60 days per token rather than by family lifetime, so a long-lived device does not accumulate history forever.

## Logging out

`POST /v1/auth/logout { refreshToken }` revokes the session the token belongs to: the whole family, under the same advisory lock as rotation, so a refresh racing the logout cannot leave a freshly minted successor alive. The refresh token is the credential here, not the JWT, so a client can log out after its JWT expired without refreshing first. Unknown, used, expired and already revoked tokens answer 204 as well; clients call it fire-and-forget and clear local state either way. Clearing local tokens alone never ended the session on the server: the refresh token stayed valid until it expired, 90 days by default, and the session kept showing in `GET /v1/auth/sessions`.

## OIDC providers

Any OpenID-Connect provider (Zitadel, Auth0, Keycloak, …) plugs in via the generic `Rs256TokenVerifier` + JWKS path — no provider-specific code needed, just a config entry. The frontend completes Authorization Code + PKCE against the provider in the browser; the backend verifies the resulting `id_token` and runs the same upsert + JWT-mint flow as Firebase.

Two ways to configure:

**HOCON map**, for multiple providers — edit `application.conf` (or an overlay):

```hocon
oidc {
  providers {
    zitadel {
      issuer   = "https://my-instance.zitadel.cloud"
      audience = "my-zitadel-app-id"
      # jwks-uri is optional; if omitted, it's discovered via
      # <issuer>/.well-known/openid-configuration
    }
    auth0 {
      issuer   = "https://my-tenant.auth0.com/"
      audience = "my-auth0-client-id"
    }
  }
}
```

Each entry becomes `POST /v1/auth/oidc/<name>` (`/v1/auth/oidc/zitadel`, `/v1/auth/oidc/auth0`).

**Env single slot**, for the common single-provider case:

```
OIDC_PROVIDER_NAME=oidc       # path segment, default "oidc"
OIDC_ISSUER=https://...
OIDC_AUDIENCE=client-id
OIDC_JWKS_URI=                # optional override; otherwise discovered
```

(When the env slot's name collides with a HOCON `oidc.providers` entry, the env slot wins.)

The verifier checks the token's signature against the provider's JWKS (cached for 1 h; while the cache is fresh, an unknown `kid` is rejected rather than triggering a refetch — the route is unauthenticated, so a refetch-on-unknown-kid path would let arbitrary callers force outbound JWKS / discovery calls. Key rotation is picked up on the next cache-expiry refresh, so a freshly-rotated kid can lag by up to the cache window). The verifier also validates `iss` matches the configured issuer (trailing-slash-insensitive), requires the token's `aud` to intersect the configured audience set, validates `azp` against the configured audience when the token is multi-audience (per the OIDC ID-Token spec), and accepts ±30 s clock skew on `exp` / `nbf` / `iat`. `audience` is comma-split, so `OIDC_AUDIENCE=client1,client2` accepts tokens with either in `aud`.

Same-email users from different providers are **separate** accounts — `UserAuth` is keyed by `(provider, providerUserId)`. Linking-by-verified-email is a separate, security-sensitive feature, not done out of the box.

## Dev login

`POST /v1/auth/dev { "email": "alice@example.com" }` synthesises a verified token from the email and logs you in (creating the user on first call, with `emailVerified = true` and `provider = "Dev"`). Gated by `dev-auth.enabled` (`DEV_AUTH_ENABLED`) — when the flag is off the route still exists but returns 404 `unknown-provider`. Default `.env.sample` ships `DEV_AUTH_ENABLED=true` so the stock checkout has a working login without any external provider configured; flip to `false` (or unset) for any deploy where dev login shouldn't be reachable.

## The auth gate

`ApplicationLoader.routes(wsb)` wraps everything under `/v1` in `authenticateOrRejectWithChallenge(userAuthenticator)`:

```scala
apiVersionPrefix {
  authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
    route(auth) ~ wsRoutes(auth, wsb)        // authed routes
  } ~
  (route ~ wsRoutes(wsb))                    // public routes (auth optional)
}
```

`UserAuthenticator` decodes the `Authorization: Bearer …` header. Success → `Right(AuthContext)`, failure → a `WWW-Authenticate: Bearer realm="madrileno"` challenge with status 401.

A route inside the authenticated branch accepts the `AuthContext` parameter; a route outside it doesn't. `RouteProvider` (no auth) and `AuthRouteProvider` (auth required) are the two flavours; modules pick which one each route belongs to.

## Swapping the provider

Three points control where identity comes from:

- **`ExternalAuthVerifier` trait.** One method: `verifyToken(token: ExternalAuthToken): IO[Either[Throwable, VerifiedExternalToken]]`. Implement it against any provider that maps a token to "this is who, with these attributes."
- **`AuthModule.externalAuthVerifiers: AuthVerifiers`** — a `Map[Provider, ExternalAuthVerifier]` (a `protected lazy val`). Override in a sub-trait or concrete `ApplicationLoader`; tests do this with `FakeAuthVerifier` (see `TestApplicationLoader`).
- **For OIDC OPs**, you don't write code — add an entry to `oidc.providers` (or set the `OIDC_*` env slot). `POST /v1/auth/oidc/<name>` is generic.
- **For non-OIDC providers**, implement `ExternalAuthVerifier`, wire it into the verifier map under a new `Provider("name")` constant, and add a dedicated route (mirroring `/v1/auth/firebase` or `/v1/auth/dev`). The downstream upsert / JWT-mint / refresh-token machinery is provider-agnostic via `AuthenticationService.authenticateWithProvider`.

For browser-based OIDC (Authorization Code + PKCE), this template assumes the frontend does the redirect handshake; the backend only verifies the resulting `id_token`. If you need a backend-driven flow (the server owns `/start` and `/callback`), that's an extra pair of endpoints around `Rs256TokenVerifier` — same downstream from there.

## Account deletion

`DELETE /v1/users/me` (`UserService.deleteAccount`, in the `user` module) runs in one transaction: anonymize the `user` row in place (PII columns nulled, `deleted_at` set — the id and audit timestamps survive for referential integrity) and publish `UserAccountDeleted` through the [outbox](outbox.md). The event is published only when the anonymize actually transitioned the row, so the endpoint is idempotent: the second call is a no-op and both return 204. Everything else is an outbox consumer's job, not the request's: auth's consumer (`AuthAccountCleanupService`) soft-deletes all `user_auth` provider identities and revokes all refresh tokens, and the auction consumer cancels the user's auctions and mails bidders. Auth depends on user, never the other way round.

Until auth's consumer has run, the deleted account's identities and refresh tokens are still live rows, but they lead nowhere. Issuing tokens looks the user up, so a refresh for a deleted user returns `InvalidToken`, and a login whose identity points at a deleted user runs the same cleanup inline before registering.

Signing in again with the same provider identity is a *fresh registration* — the soft-deleted `user_auth` row no longer matches, so a new `User` is created. That's the intended semantics of "delete", not a bug.

**The residual access-token window.** Refresh tokens stop working with the account, but already-issued JWTs are stateless and stay valid until they expire. During that window `GET /v1/users/me` returns 401 `account-deleted` (the router treats "authenticated user's row is gone" as a dead session, so clients drop their tokens), while other authenticated endpoints that load the user may surface errors. This transiently-inconsistent window is accepted and bounded by the JWT lifetime; closing it for real means a revocation check on every request (a denylist or a `deleted_at` lookup in the auth gate), which this template deliberately doesn't spend a DB round-trip on.

Industry-standard companions that are deliberately **not** pre-built, in the spirit of [What you can't do](#what-you-cant-do):

- **Recent-auth ("sudo mode").** Big providers require a fresh re-authentication before destructive account actions; here any valid JWT suffices. The hook point is the `DELETE /users/me` route — gate it on a recently-issued token (`iat` claim) or a re-verified provider token.
- **Grace period / undo.** Common practice is a 14–30 day window where the account is deactivated but recoverable. Here deletion is immediate and final; a grace period means deferring the anonymize+event behind a scheduled task that a re-login cancels.
- **Pre-deletion data export.** GDPR-adjacent flows usually offer "download your data" first; there's no export endpoint.

## Credentials in logs

The service and the bearer authenticator never write a credential to a log line. Where a line needs to say *which* token it is about, it carries a `Fingerprint` instead: `fp:` plus the first 32 bits of an HMAC-SHA256 of the value under `logging.fingerprint-secret` (`FINGERPRINT_SECRET`). The same token yields the same fingerprint everywhere in a deployment, so an unknown-token warning, a replay warning and the issue line can be joined on it, while the fingerprint itself is useless without the key: being keyed, it is safe even for low-entropy inputs such as a dev-login email or a malformed value a client sent by mistake. Refresh-token row ids and family ids appear raw; they are handles, not credentials.

## What you can't do

- **Change the internal JWT's signing scheme to RS256/asymmetric.** `JwtService` uses HMAC. Easy to extend — provide a key pair and switch the algorithm — but not done out of the box.
- **Have multiple internal-JWT secrets / rotate them.** Single static secret. For rotation you'd add key-id support and a small key resolver in `JwtService`.
- **Federate identity across multiple providers per user.** `UserAuth` is one row per `(provider, providerUserId)`, and `User` has one canonical id. To allow "same user signs in via Google AND Apple," you'd link multiple `UserAuth` rows to one `User`.

None of these are deep changes; they're just not pre-built.

## Testing

`FakeAuthVerifier` returns a fixed `VerifiedExternalToken` for any input *except* its `invalidTokenValue` (default `"invalid-token"`), which it rejects — that's what lets a route spec assert the 401 path without setting up real signature verification. `TestApplicationLoader` wires it under both `Provider.Firebase` and `Provider("test-oidc")`, so route specs can `POST /v1/auth/firebase` or `POST /v1/auth/oidc/test-oidc` with any other string and get a predictable user back.

`Rs256TokenVerifierSpec` covers the RS256 verification core directly — an in-test RSA keypair signs tokens via `java-jwt`, and the verifier is exercised with a manual key resolver (happy path, wrong issuer / audience, foreign signing key, expired token, key-resolver failure on unknown kid, missing `iat` / `exp`, etc.).

`TestData.authContext()` builds an `AuthContext` for service-level tests that don't go through the auth flow at all.

For an end-to-end test that exercises the JWT path: log in via the test loader, capture the returned JWT, send it as `Authorization: Bearer …` on the next request. The same `userAuthenticator` decodes it.

## Where to look next

- [http.md](http.md) — `AuthRouteProvider`, the gate, and how `AuthContext` flows into `route(auth)`.
- [scheduler.md](scheduler.md) — `cleanupExpiredRefreshTokensTask` is a recurring task; the same pattern applies to anything you need on a schedule.
- [error-handling.md](error-handling.md) — `AuthenticationResult` is one of the canonical Result-ADT examples.
