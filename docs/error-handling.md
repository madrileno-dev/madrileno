# Error handling

The project distinguishes two things that other codebases tend to fold together:

- **Domain rejections** — every legitimate non-success answer a service can give. "Auction not found", "bid too low", "not owner". These are *replies*, not errors. They flow through `IO[Result]` where `Result` is a sealed ADT, and they're surfaced to clients as 4xx responses with a domain-specific `type` tag.
- **Infrastructure errors** — Postgres is unreachable, S3 timed out, the Firebase JWT verifier choked on garbage. These are real exceptions. They flow through `IO`'s error channel, never participate in domain logic, and surface as 500 responses with a trace-id pointing to the trace that captured them.

Keeping them in separate channels is the discipline that makes services predictable. If you can't tell which channel something belongs in, it almost always belongs in the result ADT.

## Domain rejections live in the result ADT

Every write-side service method returns `IO[ResultADT]` where the ADT names every possible outcome:

```scala
enum PlaceBidResult {
  case BidPlaced(bid: Bid, auction: Auction)
  case AuctionNotFound
  case AuctionNotOpen
  case AuctionNotStarted
  case AuctionEnded
  case CannotBidOnOwnAuction
  case AlreadyHighestBidder
  case BidTooLow(minAmount: Price)
}
```

The router pattern-matches and produces an HTTP response per case:

```scala
auctionService.placeBid(command).map[ToResponseMarshallable] {
  case PlaceBidResult.BidPlaced(bid, _)         => Created -> BidDto(bid)
  case PlaceBidResult.AuctionNotFound           => error(NotFound, "auction-not-found", "Auction not found")
  case PlaceBidResult.AuctionNotOpen            => error(Conflict, "auction-not-open", "Auction is not open")
  case PlaceBidResult.AuctionNotStarted         => error(Conflict, "auction-not-started", "Auction has not started yet")
  case PlaceBidResult.AuctionEnded              => error(Conflict, "auction-ended", "Auction has already ended")
  case PlaceBidResult.CannotBidOnOwnAuction     => error(Forbidden, "cannot-bid-on-own-auction", "Cannot bid on your own auction")
  case PlaceBidResult.AlreadyHighestBidder      => error(Conflict, "already-highest-bidder", "You already have the highest bid")
  case PlaceBidResult.BidTooLow(minAmount) =>
    error(Conflict, "bid-too-low", "Bid is below the current minimum", extension = BidTooLowExtension(minAmount))
}
```

Three rules drop out:

- **Services don't throw for domain reasons.** "Not found" is a result, not an exception. `IO.raiseError` is reserved for infrastructure problems.
- **The router has one match per result.** Compiler-checked exhaustiveness; adding a case to the ADT breaks the router build until you handle it.
- **The error envelope's `type` is a stable contract.** `auction-not-found`, `bid-too-low` — clients can dispatch on them; UI strings can change without breaking integrations.

See [domain-modeling.md](domain-modeling.md) for the ADT side and [sealed-monad.md](sealed-monad.md) for the for-comprehension that produces them.

## The RFC 9457 envelope

All error responses (rejections, validation failures, internal errors) use the same `Error[T]` envelope, modeled on [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) (the current problem-details standard, obsoleting RFC 7807):

```scala
final case class Error[T: Encoder](
  `type`: Option[URI] = Some(URI.create("about:blank")),
  status: Option[Status] = None,
  title: Option[String] = None,
  detail: Option[String] = None,
  instance: Option[URI] = None,
  extension: T = ()
)
```

The shape on the wire:

```json
{
  "type":      "result:bid-too-low",
  "status":    409,
  "title":     "Bid is below the current minimum",
  "instance":  "trace-id:9f0c…",
  "minAmount": 350.00
}
```

| Field       | What it carries                                                                                       |
| ----------- | ----------------------------------------------------------------------------------------------------- |
| `type`      | A URI-shaped tag identifying the kind of error. Clients dispatch on this. Stable; treat as contract. |
| `status`    | HTTP status code, repeated in the body for clients that don't see the response line.                  |
| `title`     | Human-readable summary. Localizable; not stable.                                                       |
| `detail`    | Optional longer explanation. Renderable to the user.                                                   |
| `instance`  | The active trace-id, encoded as `trace-id:<hex>`. Paste into OpenObserve to see the trace.            |
| `extension` | Per-error structured data, merged into the envelope as top-level members (RFC 9457 §3.2). `()` when there is nothing to add; otherwise a small case class whose fields appear beside the five above. |

`type` follows a small naming convention:

- `result:<kebab>` — domain rejection (`result:bid-too-low`, `result:auction-not-found`).
- `rejection:<kebab>` — framework-level rejection from the routing layer (`rejection:static-validation-failed`, `rejection:malformed-request-content`).
- `error:<kebab>` — internal error (`error:internal-error`).

Clients shouldn't try to parse it as a URL. It's a tag.

## The `error(...)` helper

`BaseRouter.error` builds the envelope, looks up the active trace, and returns a response-marshallable:

```scala
def error[E: Encoder](
  status: Status,
  typeTag: String,
  title: String,
  detail: Option[String] = None,
  extension: E = ()
)(using tc: TelemetryContext): IO[ToResponseMarshallable]
```

Use it from any router. The trace-id is filled in automatically; you supply the status, the type tag, and the human-facing title. **Keep the title generic and put any data the client needs in `extension`** — never make a client parse the human `title`. This is the canonical pattern for rejections that carry a value:

```scala
final case class BidTooLowExtension(minAmount: Price) derives Encoder.AsObject, Decoder

case PlaceBidResult.BidTooLow(minAmount) =>
  error(Conflict, "bid-too-low", "Bid is below the current minimum", extension = BidTooLowExtension(minAmount))
```

The case class encodes into top-level members of the envelope (`"minAmount": 350.00` beside `type`, `status`, …), and the frontend reads `minAmount` from the problem and renders/localizes the message itself. Always use a case class, not a `Map`: a `Map` serializes the same way but carries no field names into the schema, so the generated clients never see the field. In the route spec, declare the response as `Error[BidTooLowExtension]`; `BaseRouteSpec` derives the schema for `Error[E]` from `E`'s own schema, named `<E>Error` (`BidTooLowExtensionError` in the OpenAPI components), and the extension fields show up typed in the generated oRPC contract.

## Framework rejections

Stir's routing layer rejects things before your service even runs: missing query params, malformed JSON, unsupported methods, failed authentication. These never become exceptions; they become `Rejection` values that the configured `RejectionHandler` turns into responses.

`Handlers.rejectionHandler` lists the cases the project handles explicitly. The pattern: every `Rejection` subtype gets a clause that builds an `Error` with `type = "rejection:<name>"` plus, where useful, structured `extension` data.

| Rejection                                  | Status | Type tag                                  |
| ------------------------------------------ | ------ | ----------------------------------------- |
| `MalformedRequestContentRejection`         | 400    | `rejection:static-validation-failed` (when JSON decoding fails) or `rejection:malformed-request-content` |
| `MalformedRequestContentRejection` caused by `EntityTooLarge` | 413 | `rejection:payload-too-large` (the body exceeds `http.max-request-size`) |
| `MalformedFormFieldRejection`              | 400    | `rejection:malformed-form-field`          |
| `MalformedHeaderRejection`                 | 400    | `rejection:malformed-header`              |
| `MalformedQueryParamRejection`             | 400    | `rejection:malformed-query-param`         |
| `MissingHeaderRejection`                   | 400    | `rejection:missing-header`                |
| `MissingQueryParamRejection`               | 400    | `rejection:missing-query-param`           |
| `InvalidRequiredValueForQueryParamRejection` | 400  | `rejection:invalid-required-value-for-query-param` |
| `MissingFormFieldRejection`                | 400    | `rejection:missing-form-field`            |
| `MissingCookieRejection`                   | 400    | `rejection:missing-cookie`                |
| `UnsupportedRequestContentTypeRejection`   | 415    | `rejection:unsupported-request-content-type` |
| `RequestEntityExpectedRejection`           | 400    | `rejection:request-entity-expected`       |
| `UnacceptedResponseContentTypeRejection`   | 406    | `rejection:unaccepted-response-content-type` |
| `ExpectedWebSocketRequestRejection`        | 426    | `rejection:expected-web-socket-request`   |
| `ValidationRejection`                      | 400    | `rejection:static-validation-failed`      |
| `SchemeRejection`                          | 400    | `rejection:scheme`                        |
| `MethodRejection`                          | 405    | `rejection:method`                        |
| `AuthorizationFailedRejection`             | 401    | `rejection:authorization-failed`          |
| `AuthenticationFailedRejection`            | 401    | `rejection:authentication-failed`         |
| Route not found                            | 404    | `rejection:route-not-found`               |

Adding a new rejection means adding a clause; if you forget, stir falls back to a less informative default. Keep the list of clauses in sync with the rejection types in use.

## Infrastructure errors

`IO`'s error channel is reserved for things that genuinely went wrong:

- **Database errors** — Postgres connection lost, deadlock detected, query timeout.
- **External-service failures** — S3 timeout, Firebase JWT verification crash, SMTP unreachable.
- **Resource limits hit** — `ObjectTooLarge` raised by `ObjectStore.fetchBytes` when the object exceeds the configured cap.
- **Bugs** — illegal states reached because of a code mistake, not a request.

These are caught by `Handlers.exceptionHandler` and rendered as a 500 with `type = "error:internal-error"`. The body carries the trace-id so an incident can be tracked from the user-visible response back through the logs and traces.

```scala
case e: Exception =>
  for {
    spanContext <- tc.tracer.currentSpanContext
    headers     <- tc.tracer.propagate(Headers.empty)
    _           <- logger.error(e)(s"Internal error, trace-id: ${spanContext.map(_.traceIdHex).getOrElse("000")}\n${e.getMessage}")
  } yield (InternalServerError, headers, Error(/* error:internal-error envelope */))
```

`IllegalArgumentException` is treated specially — it short-circuits to 400 with `rejection:static-validation-failed`. That's because kebs's `validate` and similar guards throw `IllegalArgumentException` for value-construction failures that managed to reach a handler unprotected. It's a safety net, not a primary error path; the right place for those is at the codec boundary, before the value reaches any service.

## When to use `IO.raiseError`

Three legitimate reasons for application code to raise:

- **Compensation can't fail silently.** From `attachImage`: storage put succeeded, DB persist failed. Cleanup the orphaned blob, log loudly, *then* re-raise — the request can't return a meaningful result, and silent failure would hide a real problem.

  ```scala
  result <- persistAttached(...).attempt.flatMap {
              case Right(other) => …
              case Left(t)      => cleanupAfterFailedPersist(key) *> logger.error(t)(...) *> IO.raiseError(t)
            }
  ```

- **Capacity/safety boundaries.** `ObjectStore.fetchBytes` raises `ObjectTooLarge` when an object exceeds the heap-safety cap. The caller has no recovery path; the alternative is OOM. Better to fail loudly.

- **Library mismatches that aren't supposed to happen.** `DiskObjectStore.presignPut` raises `UnsupportedOperationException` because the disk backend can't generate signed URLs. Configuration mistake, surface with a stack trace.

What `raiseError` is *not* for:

- "Not found." That's a domain result.
- "Validation failed." That's a domain result (or a codec rejection at the boundary).
- "Permission denied." That's a domain result.
- Anything you'd want to render with a specific 4xx status. If it has a stable type tag and a sensible client-facing message, it belongs in the result ADT.

## Compensation patterns

Two-phase operations (write to S3 + write to DB; send to scheduler + commit; etc.) need explicit compensation when the second phase fails. The pattern is consistent across the codebase:

```scala
for {
  blob   <- objectStore.put(...)
  result <- persistInDb(...).attempt.flatMap {
              case Right(success) => IO.pure(success)
              case Left(t)        =>
                cleanupAfterFailedPersist(blob) *>
                  logger.error(t)(s"Persist failed for ${blob.render}") *>
                  IO.raiseError(t)
            }
} yield result
```

Three pieces:

- **`.attempt`** turns the failure-prone step into `IO[Either[Throwable, A]]` so the compensating step is in scope of the failure.
- **The compensation is itself effectful** — `cleanupAfterFailedPersist` retries with `attempt` to swallow secondary failures, since orphan-blob cleanup failure isn't worse than orphan-blob persist failure.
- **Re-raise after compensating.** The original failure still has to surface; otherwise the caller would see "success" with nothing actually persisted.

Don't try to make this transactional with the database — the storage layer isn't transactional. Compensation is what you have. If the cleanup itself fails, you log and accept the orphan; that's why monitoring matters more than perfect rollback.

## At the boundary: codec validation

Before any service runs, request bodies decode through circe codecs. Opaque-type validators (see [domain-modeling.md](domain-modeling.md)) are wired into those codecs, so invalid inputs (`""` for `WineName`, negative `Price`) fail at decoding. The framework turns those into `MalformedRequestContentRejection` carrying the original `DecodingFailure`, and the rejection handler renders them as 400 with `type = rejection:static-validation-failed`, including the JSON path:

```json
{
  "type": "rejection:static-validation-failed",
  "status": 400,
  "title": "Validation failed",
  "detail": ".wineName: Wine name must not be empty",
  "instance": "trace-id:…"
}
```

This is why services don't validate inputs themselves: the values they receive are already validated by virtue of being typed.

## Errors in events, jobs, WebSockets

The same channel split applies away from the request boundary, with adjustments:

- **Scheduler tasks** (see [scheduler.md](scheduler.md)) — `IO`-failed tasks are retried with exponential backoff. If the task can fail with a domain reason that *isn't* worth retrying ("the entity that triggered this got deleted"), return `IO.unit` after logging; don't raise.
- **Event-bus publishers** (see [event-bus.md](event-bus.md)) — `publish` is wrapped in `attempt` and logged; failure is not allowed to roll back the operation that triggered it. The bus is best-effort; durable side-effects belong in the scheduler.
- **WebSockets** (see [websockets.md](websockets.md)) — `droppingBuffer` and connection lifecycle handle slow / disconnected clients. Server-side errors close the socket; clients reconnect.
- **Outbound HTTP** (see [external-apis.md](external-apis.md)) — gateways usually fold transport failures, 5xx, and decoding errors into the domain answer (`None`, an ADT). Only re-raise if the caller has no sensible handling.

## Where to look next

- [domain-modeling.md](domain-modeling.md) — result ADTs, opaque-type validators, the codec boundary.
- [sealed-monad.md](sealed-monad.md) — composing the `Either[Rejection, _]` operations that feed the result ADT.
- [observability.md](observability.md) — the trace-id in `instance`, where to follow it in OpenObserve.
- [http.md](http.md) — `BaseRouter.error`, the rejection handler at the top of the route stack.
