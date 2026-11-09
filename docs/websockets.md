# WebSockets

WebSockets are first-class in the routing layer. A module mixes in `WsRouteProvider` (or `AuthWsRouteProvider`) to declare WS endpoints, the same way it declares HTTP routes. The `WebSocketBuilder2[IO]` instance flows from http4s through `Main` into `routes(wsb)`, then into each module's `wsRoutes(wsb)` method.

The most common pattern in this template is "subscribe to an event-bus topic and project events to JSON frames" — auction bid updates being the worked example. The same pattern applies to any `Stream[IO, T]` source.

## The shape

```scala
trait WsRouteProvider {
  def wsRoutes(wsb: WebSocketBuilder2[IO]): Route
}
```

A module overriding it:

```scala
trait AuctionModule extends … with WsRouteProvider {
  // …
  override abstract def wsRoutes(wsb: WebSocketBuilder2[IO]): Route =
    super.wsRoutes(wsb) ~ auctionRouter.wsRoutes(wsb)
}
```

The router's `wsRoutes(wsb)` is plain stir directives plus `handleWebSocketMessages`. The auction module exposes two routes — the firehose (`/auctions/stream`, every auction) and a per-lot stream (`/auctions/{auctionId}/stream`) — sharing one helper parameterised by a predicate over events:

```scala
def wsRoutes(wsb: WebSocketBuilder2[IO]): Route = {
  def auctionEventStream(keep: AuctionEvent => Boolean): Route = {
    val send: Stream[IO, WebSocketFrame] =
      Stream
        .resource(eventBus.subscribeAwait)
        .flatMap(_.filter(keep)
                  .droppingBuffer(capacity = 256)
                  .map(e => WebSocketFrame.Text(AuctionEventEnvelope(e).noSpaces)))
    handleWebSocketMessages(wsb, send, _.drain)
  }
  (get & path("auctions" / "stream") & pathEndOrSingleSlash) {
    auctionEventStream(_ => true)
  } ~ (get & path("auctions" / JavaUUID.as[AuctionId] / "stream") & pathEndOrSingleSlash) { auctionId =>
    auctionEventStream(_.auctionId == auctionId)
  }
}
```

`.filter(keep)` sits before `droppingBuffer`, so a client on the scoped stream buffers only that auction's events, not the whole firehose.

`handleWebSocketMessages(wsb, send, receive)` takes:

- the `WebSocketBuilder2[IO]` from the routing context,
- a **send** stream — what the server pushes to the client,
- a **receive** pipe — what to do with frames the client sends; `_.drain` ignores them.

For chat-style two-way conversations the receive pipe does the actual work; for one-way push streams (like our bid feed) you `_.drain`.

## The auction worked example

```
DB write → service publishes AuctionEvent → in-process / Postgres NOTIFY topic
                                                       │
                                                       ▼
                                          eventBus.subscribeAwait
                                                       │
                                                       ▼
                                          droppingBuffer(256)
                                                       │
                                                       ▼
                                       AuctionEventEnvelope(event).noSpaces
                                                       │
                                                       ▼
                                          WebSocketFrame.Text(json)
                                                       │
                                                       ▼
                                                   browser
```

The router subscribes once per WS connection. Behind the scenes both backends (`local` fs2 Topic, `postgres` LISTEN/NOTIFY) fan out events to all subscribers — see [event-bus.md](event-bus.md).

`droppingBuffer(256)` is from `BaseRouter`. It's a per-connection bounded buffer; when full, new events are silently dropped (the implementation uses `Queue.bounded` + `tryOffer`, so it's the *newest* event that's discarded, not the oldest — despite the name). Without it, a slow client would back-pressure the bus and slow down everyone else. Pick a buffer size big enough to absorb realistic spikes (a few seconds of high-frequency events); too small and you drop on every blink, too big and a stuck client wastes memory.

## Internal events vs public frames

The `AuctionEvent` enum is the internal NOTIFY format. The frames pushed to the client are something else — a `{kind, data}` envelope shaped for stable client consumption:

```scala
object AuctionEventEnvelope {
  def apply(event: AuctionEvent): Json = {
    val (kind, data) = event match {
      case e: AuctionEvent.AuctionCreated   => "AuctionCreated"   -> AuctionCreatedDto(e).asJson
      case e: AuctionEvent.BidPlaced        => "BidPlaced"        -> BidPlacedDto(e).asJson
      case e: AuctionEvent.AuctionCancelled => "AuctionCancelled" -> AuctionCancelledDto(e).asJson
      case e: AuctionEvent.AuctionClosed    => "AuctionClosed"    -> AuctionClosedDto(e).asJson
    }
    Json.obj("kind" -> kind.asJson, "data" -> data)
  }
}
```

Two reasons to project this way:

- **The wire shape is a public contract.** Consumers depend on it. Renaming an enum case shouldn't break the frontend.
- **Internal events carry too much.** The domain enum knows about `AuctionEvent.BidPlaced(auctionId, amount, currency, createdAt)`. The DTO deliberately drops the bidder identity (auction privacy) and keeps `createdAt` consistent with the rest of the public API. Don't expose internals just because circe will serialize them.

The `kind` discriminator is a flat string so naive clients can switch on it without a JSON-schema'd union type. The `data` payload is the per-case DTO, encoded with circe.

## Authenticated WebSockets

`AuthWsRouteProvider` — same shape as `WsRouteProvider` but its method takes an `AuthContext`:

```scala
def wsRoutes(auth: AuthContext, wsb: WebSocketBuilder2[IO]): Route = …
```

`ApplicationLoader.routes(wsb)` wraps these inside the `authenticateOrRejectWithChallenge(...)` directive, so auth is gated the same way as authed HTTP routes — the client passes `Authorization: Bearer <jwt>` on the WS handshake. (Browsers can't set arbitrary headers on `new WebSocket(...)`, so production auth flows usually pass the JWT as a query parameter or via a short-lived presigned ticket; not bundled, but easy to add.)

The auction module currently uses the unauthenticated `WsRouteProvider` for the public stream — anyone can listen to bid events. Server-side filtering doesn't need auth, though: `GET /v1/auctions/{auctionId}/stream` narrows the same feed to one lot with a plain `Stream.filter`. For per-user feeds, switch to `AuthWsRouteProvider` and filter by `auth.userId` instead.

## Receive paths

When the client sends frames you want to handle, replace `_.drain` with a pipe that does the work:

```scala
val receive: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
  case WebSocketFrame.Text(payload, _) =>
    parser.decode[InboundCommand](payload) match {
      case Right(cmd) => handle(cmd)
      case Left(err)  => logger.warn(err)(s"Bad WS frame: $payload")
    }
  case _ => IO.unit  // ignore non-text frames
}
handleWebSocketMessages(wsb, send, receive)
```

`Pipe[IO, WebSocketFrame, Unit]` is fs2's "stream-in, stream-out" type. The `Unit` here means we drain inbound frames as side effects — typical for command-style WS APIs. If you want to acknowledge each command, return a `Pipe[IO, WebSocketFrame, WebSocketFrame]`.

To answer each command on the same connection while the broadcast keeps flowing, allocate a per-connection reply queue and merge it into the send stream. Call `wsb.build` directly instead of `handleWebSocketMessages`, so the queue exists per connection:

```scala
def wsRoutes(auth: AuthContext, wsb: WebSocketBuilder2[IO]): Route =
  (get & path("auctions" / "stream") & pathEndOrSingleSlash) {
    complete {
      for {
        replies <- Queue.bounded[IO, WebSocketFrame](capacity = 64)
        broadcast = Stream.resource(eventBus.subscribeAwait).flatMap(_.droppingBuffer(capacity = 256))
        send      = broadcast.map(e => WebSocketFrame.Text(AuctionEventEnvelope(e).noSpaces)).merge(Stream.fromQueueUnterminated(replies))
        receive: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
          case WebSocketFrame.Text(json, _) => handleCommand(json, auth, replies)
          case _                            => IO.unit
        }
        response <- wsb.build(send, receive)
      } yield response
    }
  }
```

`droppingBuffer` goes on the broadcast only: replies (acks, validation errors) are few and belong to this client, so a flood of broadcast events must not drop them. `handleCommand` decodes the frame, calls the existing service method and offers a reply correlated by a client-sent request id; the service doesn't change. `evalMap` handles one command at a time per connection; `parEvalMap(n)` processes them concurrently.

## Connection lifecycle

`Stream.resource(eventBus.subscribeAwait)` ties the bus subscription's lifetime to the WebSocket: when the client disconnects, http4s tears down the receive/send streams, the resource finalizer fires, the subscription unsubscribes. No cleanup code to write.

The same pattern applies to anything else with a lifecycle — DB sessions, presigned-URL leases. Wrap it in `Stream.resource(...)` and the close-on-disconnect behaviour comes for free.

## Backpressure

The send stream is the producer; the WebSocket frame writer is the consumer. fs2 backpressure flows naturally — if the network is slow, frames pile up, the bus subscription's `Topic` enforces `maxQueued`, and `droppingBuffer` is the explicit safety valve when you'd rather drop than block.

If you don't add `droppingBuffer`, a stalled client doesn't immediately break — but if the bus's `maxQueued` fills up, the bus side will start dropping for that subscriber too. Always have a clear "what happens when the client can't keep up" answer; `droppingBuffer` makes it explicit.

## Testing

Service- and event-bus-level logic is testable without WebSockets at all. For end-to-end WS tests, http4s ships an in-memory client; integrate it in baklava router specs the same way HTTP request specs work. The auction module ships baklava specs for both WS routes (`/v1/auctions/stream` and `/v1/auctions/{auctionId}/stream`) — OpenAPI 3.x can't model WebSockets, so these are discoverability stubs: they record the path (and the `auctionId` param) and assert the 426 plain-GET fallback, while the frame format is documented in prose above. The send/receive streams themselves aren't exercised by an in-memory WS client in the suite yet.

## Where to look next

- [event-bus.md](event-bus.md) — the source of WS frames in the worked example.
- [http.md](http.md) — `WsRouteProvider` and `AuthWsRouteProvider` are next to the HTTP route providers; `routes(wsb)` is where they all combine.
- [auth.md](auth.md) — JWT handshake details for `AuthWsRouteProvider`.
