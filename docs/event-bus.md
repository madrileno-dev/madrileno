# Event bus

The event bus is the application's pub-sub layer for in-process notifications: a service publishes `AuctionEvent.BidPlaced(...)`, a WebSocket router subscribes and projects updates to clients. Same API regardless of whether the underlying transport is in-memory (single-process dev/tests) or Postgres `LISTEN/NOTIFY` (multi-process production).

It does NOT replace the scheduler, durable queues, or external message brokers — see "Use it for what it's for" below.

## The shape

```scala
trait EventBus[E] {
  def publish(event: E): IO[Unit]
  def subscribe: Stream[IO, E]
  def subscribeAwait: Resource[IO, Stream[IO, E]]
}

trait EventBusRuntime {
  def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E]
}
```

Modules ask `eventBusRuntime` for a typed topic, store it as a `lazy val`, and use it for both publishing and subscribing. To share a topic across modules, declare the `EventBus[E]` once (e.g. in `ApplicationLoader` or a parent module) and pass the reference down — `runtime.topic(name, maxQueued)` constructs a fresh instance on every call. With the Postgres backend, multiple instances sharing the same channel name still observe each other's events via `LISTEN/NOTIFY`; with the local backend they don't, so don't rely on per-name identity.

## Two backends, same interface

| Backend                 | Construction                       | Use case                                                                          |
| ----------------------- | ---------------------------------- | --------------------------------------------------------------------------------- |
| `EventBusRuntime.local` | In-memory `fs2.concurrent.Topic`   | Single-process apps, dev, tests. Subscribers are JVM-local; nothing crosses processes. |
| `EventBusRuntime.postgres(transactor)` | Postgres `LISTEN/NOTIFY` | Multi-instance production. Every JVM that subscribes to a channel gets every event published anywhere. |

`Main` picks the backend. `EventBusRuntime.postgres(transactor)` is the production choice; `EventBusRuntime.local` is what `TestApplicationLoader` uses. The same module code works against either.

## Defining an event type

Events are Scala 3 enums (or sealed traits) with `derives EventCodec`. The derived codec is JSON via circe under the hood and serializes the enum case as a discriminator-shaped object.

```scala
enum AuctionEvent derives EventCodec {
  def auctionId: AuctionId
  def createdAt: Instant

  case AuctionCreated(auctionId: AuctionId, wineName: WineName, …)
  case BidPlaced(auctionId: AuctionId, amount: Price, …)
  case AuctionCancelled(auctionId: AuctionId, createdAt: Instant)
  case AuctionClosed(auctionId: AuctionId, winningBid: Option[Price], …)
}
```

Two conventions:

- **The enum is internal.** Don't ship this shape to clients. Project to a public DTO in the router. See "WebSocket projection" in [websockets.md](websockets.md). The internal NOTIFY format is yours to evolve; the public contract isn't.
- **All cases share the abstract members** (`auctionId`, `createdAt`). Keeps subscribers from having to pattern-match just to log a correlation field. Add fields to the trait, not to individual cases, when every variant should carry them.

## Wiring a topic into a module

```scala
trait AuctionModule extends … {
  val eventBusRuntime: EventBusRuntime
  // …
  protected lazy val auctionEventBus: EventBus[AuctionEvent] =
    eventBusRuntime.topic[AuctionEvent]("auction_events", maxQueued = 64)

  private val auctionService = wire[AuctionService]   // injected with auctionEventBus
  private val auctionRouter  = wire[AuctionRouter]    // also injected — subscribes for WS broadcast
}
```

The topic name is the Postgres `LISTEN` / `NOTIFY` channel under the Postgres backend, so it follows Skunk's identifier rules: `[A-Za-z_][A-Za-z_0-9$]*`, no dots. `maxQueued` is the per-subscriber buffer. Slow subscribers don't block publishers — they drop events instead, with backpressure semantics matching `fs2.Topic`. Pick a number large enough to absorb the spikiest realistic burst; defaults around 32–128 are usual.

## Publishing

```scala
private def publish(event: AuctionEvent): IO[Unit] =
  eventBus.publish(event).attempt.flatMap {
    case Left(t)  => logger.warn(t)(s"Failed to publish $event")
    case Right(_) => IO.unit
  }
```

Three things to know:

- **`publish` doesn't fail your business operation.** A failed NOTIFY shouldn't roll back an auction creation. Wrap in `attempt` and log; the auction is still created, the notification was best-effort.
- **It's not transactional.** A `publish` issued inside `transactor.inTransaction { … }` will fire even if the transaction later rolls back. If you need transactional outbound notifications, write to an outbox table and let a scheduler task drain it. The auction module does the simpler thing: publish-after-commit, accept that a crash between commit and publish loses the notification.
- **Postgres NOTIFY payloads are capped at 8000 bytes.** Events are JSON-serialized and dropped into the channel. Big events (long strings, lots of fields) approach this limit. If you find yourself near it, project to a smaller event with just the IDs and re-fetch in subscribers.

## Subscribing

```scala
def wsRoutes(wsb: WebSocketBuilder2[IO]): Route =
  path("auctions" / JavaUUID.as[AuctionId] / "events") { auctionId =>
    onSuccess(eventBus.subscribeAwait.allocated) { case (stream, finalize) =>
      val frames = stream
        .filter(_.auctionId == auctionId)
        .map(event => WebSocketFrame.Text(toEnvelope(event).asJson.noSpaces))
        .onFinalize(finalize)
      handleWebSocket(wsb, frames)
    }
  }
```

Use `subscribeAwait` (a `Resource[IO, Stream[IO, E]]`) when you need to know the subscription is live before doing other work — it waits for the underlying LISTEN to be established. `subscribe` returns a `Stream` directly and is fine for the common case where the subscriber is happy to start with the next event; it silently misses whatever is published while it registers, so a WebSocket handler, the entry point for its client, uses `subscribeAwait`. Delivery is live-only: no replay, nothing persisted.

For the LISTEN backend, the runtime keeps a single Postgres LISTEN session per topic and fans events out to in-process subscribers via a memoized `fs2.Topic`. This means many WebSocket clients on the same JVM share one Postgres session, not one per client.

## Failure handling

The Postgres backend is resilient to LISTEN-session failures. If the LISTEN connection drops, the runtime logs a warning, sleeps 1 second, and reconnects. Subscribers stay attached to the in-process `Topic`; they just see a gap of events that arrived during the outage. There is no replay — `LISTEN/NOTIFY` events are transient and not buffered.

If your application can't tolerate event loss across an outage, the bus is the wrong tool. Options that survive outages:

- **An outbox table** plus a scheduler task that reads it and runs the side-effect — durable, retries built in, exactly-the-side-effect-you-want semantics.
- **[pgmq](https://pgmq.github.io/pgmq/latest/)** — a Postgres extension that turns the same database you already run into a real durable message queue (visibility timeouts, dead-letter queues, message archives). Middle ground between hand-rolled outbox and a full external broker; no new infrastructure to operate.
- **An external broker** (Kafka, NATS, RabbitMQ) wired in as a separate runtime — beyond what this template ships, but the `EventBus` interface gives you a place to plug one in.

## Use it for what it's for

The bus is good at:

- WebSocket projections of internal state changes (auction → frontend).
- Cross-module hooks where one module needs to react to another's events without a hard call dependency.
- Cache invalidation across instances.

The bus is bad at:

- **Durable work queues.** Use the scheduler. NOTIFY drops events on disconnect; the scheduler retries.
- **Public APIs.** The codec shape is your wire format internally — don't expose it. Project to DTOs.
- **Heavy payloads.** 8000 bytes is the Postgres limit. Even on the local backend, big events strain memory because every subscriber holds a copy.
- **Cross-region or cross-cluster pub-sub.** Postgres LISTEN/NOTIFY is per-database. If you have multiple clusters, you need a real broker.

## Testing

`TestApplicationLoader` wires `EventBusRuntime.local`, so route specs and service specs publish and subscribe against an in-memory `fs2.Topic` — fast, deterministic, no Postgres required.

For a service spec that needs to assert "this operation publishes event X," construct the event bus directly and subscribe with `subscribeAwait`: the subscriber is registered when the `Resource` is acquired, so the operation can't publish before anyone listens, which plain `subscribe` + `start` allows. The `timeout` makes a regression that never publishes fail instead of hanging the suite:

```scala
val bus     = EventBusRuntime.local.topic[AuctionEvent]("test", maxQueued = 8)
val service = new AuctionService(…, eventBus = bus, …)

def withObservedEvent[A](action: IO[A]): IO[(AuctionEvent, A)] =
  bus.subscribeAwait.use { stream =>
    for {
      observer <- stream.take(1).compile.lastOrError.start
      result   <- action
      event    <- observer.joinWithNever
    } yield (event, result)
  }.timeout(5.seconds)
```

For an end-to-end test that exercises Postgres LISTEN/NOTIFY, use `EventBusRuntime.postgres(testTransactor)`. Slower, useful for ensuring serialization round-trips and channel naming work.

## Where to look next

- [websockets.md](websockets.md) — the canonical consumer of the event bus.
- [scheduler.md](scheduler.md) — for durable side-effects, this is the right tool.
- [json.md](json.md) — `EventCodec` is circe-derived; the same conventions apply.
