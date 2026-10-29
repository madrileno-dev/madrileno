# Transactional outbox

The outbox is the durable half of the events story ([events.md](events.md) explains how to choose between it and the transient bus). It answers one need: *module A changed state in a transaction; modules B and C must reliably react, even across a crash or deploy.*

The shape is the classic [transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html) with a per-consumer delivery ledger doing the job of an inbox table (à la NServiceBus/MassTransit): there is no broker, delivery rides on the [scheduler](scheduler.md), and consumers live in the same process. For a modular monolith that is the whole point — the guarantees come from Postgres transactions, not from infrastructure.

## The model

```
  your transaction                            delivery (one scheduler task per consumer)
┌───────────────────────────────┐           ┌──────────────────────────────────────────────┐
│ business writes               │           │ in ONE transaction:                          │
│ outbox.publishTransactionally │           │   lock ledger row FOR UPDATE                 │
│   → domain_event row          │           │   terminal status? → no-op                   │
│   → outbox_delivery row per   │  commit   │   run subscription handler                   │
│     subscribed consumer       │ ────────► │   Done → mark Completed                      │
│   → delivery task per         │           │   Drop → mark Failed (poison)                │
│     consumer (scheduled_task) │           │   Retry → raise → rollback → backoff         │
└───────────────────────────────┘           └──────────────────────────────────────────────┘

  OutboxRecovery (background fiber, every recovery-interval):
    a) events a subscribed consumer has no ledger row for  → open ledger row + task
    b) Pending ledger rows whose task vanished             → re-enqueue task
```

Two tables (`V3__outbox.sql`):

- **`domain_event`** — the append-only log: versioned `event_type` (`user-account-deleted.v1`), aggregate identity, `jsonb` payload, `occurred_at`. Rows are never updated or deleted.
- **`outbox_delivery`** — the ledger: one row per `(event_id, consumer)` with `status` (`Pending` / `Completed` / `Failed`), `last_error`, timestamps. This table is the delivery *memory*: a row in a terminal status is what stops an event from being processed twice.

## Publishing

```scala
final case class UserAccountDeleted(userId: UserId) derives EventCodec

object UserAccountDeleted {
  given DomainEventDescriptor[UserAccountDeleted] =
    DomainEventDescriptor("user-account-deleted.v1", "user", _.userId)
}

transactor.inTransaction {
  userRepository.anonymize(userId, now) *>
    outbox.publishTransactionally(UserAccountDeleted(userId))
}
```

`publishTransactionally` is `DBInTransaction`, so the compiler forces you into a transaction: the event, its ledger rows, and its delivery tasks commit atomically with your state change, or none of it does. Publish only when a transition actually happened (the account-deletion flow publishes only when `anonymize` reports a transition) — that is what makes redelivered *commands* produce exactly one *event*.

Fan-out happens at publish time against the consumers registered in this process; consumers that only exist in a newer deploy are repaired by recovery (see below).

## Subscribing

A module contributes subscriptions the same way it contributes tasks or routes:

```scala
trait AuctionModule extends OutboxSubscriptionProvider {
  override abstract def outboxSubscriptions: List[OutboxSubscription] =
    super.outboxSubscriptions :+
      OutboxSubscription[UserAccountDeleted]("auction")(auctionAccountCleanupService.onAccountDeleted)
}
```

The consumer name (`"auction"`) is the ledger identity — renaming it means the consumer is treated as brand new (see replay below). Duplicate `(consumer, eventType)` registrations fail fast at wiring. A handler receives the decoded event (and optionally a `Delivery` with the attempt number) and returns a `Reaction`.

## The delivery contract

The handler runs *inside* the delivery transaction, after the ledger row is locked `FOR UPDATE` and confirmed non-terminal. What each `Reaction` means:

- **`Done`** — handler writes and the `Completed` mark commit together. This is the exactly-once-effective guarantee: for reactions expressed as DB writes, a crash at any point either commits everything or retries from scratch against the terminal-status guard.
- **`Retry(reason)`** — the transaction **rolls back**, including everything the handler wrote. The reason is recorded as `last_error` in a separate session (without touching the `Pending` status), and the scheduler retries with exponential backoff.
- **`Drop(reason)`** — the row is marked `Failed` with the reason, and — unlike `Retry` — **the handler's prior writes commit**. Use it for poison events (an undecodable payload is auto-dropped this way). If you don't want partial writes to survive a drop, decide to drop *before* writing.

Rules that follow from the handler running in a transaction:

- **Keep effects in the ambient session.** Repository calls, `mailer.sendTransactionally`, `schedulerClient.scheduleTransactionally` — all of these commit or roll back with the delivery and are therefore effectively exactly-once. A direct side effect (an HTTP call, a raw `IO`) executes on *every attempt*, including attempts that later roll back — that is plain at-least-once, and it's on you to make it idempotent.
- **The live bus is the one sanctioned exception.** Publishing to the [event bus](event-bus.md) from a handler (as `AuctionAccountCleanupService` does after cancelling an auction) goes out immediately, not on commit — on a rollback the push was early. That is within the bus's best-effort, at-most-once contract; the retry converges the real state. Don't use the bus from a handler for anything a client must not see early.
- **Delivery must never depend on wall-clock context.** The event carries `occurredAt`; a handler may run minutes or days later (retries, recovery, a consumer added after the fact). The auction cleanup consumer deliberately uses processing-time semantics — it cancels whatever is open *now*, not what was open at event time.

## What is deliberately NOT guaranteed

- **Ordering.** Every `(event, consumer)` delivery is an independent scheduler task. Two events for the same aggregate can be handled out of order, and two deliveries for the same consumer can run concurrently. This is a real divergence from CDC-based relays (Debezium et al. preserve per-key commit order) — it buys crash-safe simplicity on the scheduler we already have. Write handlers to be commutative or state-guarded (the auction cancel is a status-guarded transition: replaying or reordering it cannot un-cancel anything). If you need per-aggregate ordering, that is a design extension: serialize on the aggregate row with `FOR UPDATE` inside the handler, or add a per-aggregate sequence check and `Retry` until the predecessor lands.
- **Bounded latency.** Normal-path latency is one scheduler poll; after failures it's backoff-shaped; after a crash between commit and task pickup it can be up to `recovery-interval`.

## New consumers replay history

Recovery repairs "events a subscribed consumer has no ledger row for" — with no time bound. That means **subscribing a new consumer (or a new event type for an existing consumer) feeds it the entire history** of those event types, `recovery-batch-size` per `recovery-interval`. This is deliberate: it's the bootstrap story, and it's also why the ledger must never be half-pruned (below).

If a new consumer should *not* process history, seed its ledger rows as already-delivered in the same deploy that introduces it:

```sql
INSERT INTO outbox_delivery (event_id, consumer, status, last_error, created_at, updated_at)
SELECT id, 'my-consumer', 'Completed', NULL, now(), now()
FROM domain_event WHERE event_type IN ('user-account-deleted.v1')
ON CONFLICT DO NOTHING;
```

Mind the throughput arithmetic for real backfills: defaults (100 events / 5 min / consumer) drain ~29k events a day. Raise `recovery-batch-size` (or temporarily lower `recovery-interval`) for a large replay.

## Failures, dead-letters, and redrive

Delivery retries ride the scheduler's backoff. After `delivery-max-retries` consecutive failures the task is abandoned and the ledger row goes to `Failed` **keeping the causal `last_error`** — that is the dead-letter queue, and it's just rows:

```sql
SELECT event_id, consumer, last_error, updated_at
FROM outbox_delivery WHERE status = 'Failed' ORDER BY updated_at DESC;
```

To redrive after fixing the cause, flip the rows back — recovery re-enqueues them within one `recovery-interval`:

```sql
UPDATE outbox_delivery SET status = 'Pending', updated_at = now()
WHERE consumer = 'auction' AND status = 'Failed';
```

Alert on dead-letter growth. The signal is the `outbox.deliveries` counter with `outcome="dropped"` (a handler `Drop`, including auto-dropped poison payloads) or `outcome="exhausted"` (retries ran out) — note the scheduler's `retries_exhausted` metric only sees the latter, because a `Drop` is a *successful* task execution. A `count(*)` on `Failed` rows is the queue-depth check. Every dead-letter is also warn-logged with its reason. Removing a cap (`delivery-max-retries` absent → retry forever) trades dead-letters for indefinite retry noise — pick per deployment.

## Recovery, deploys, multiple nodes

The `OutboxRecovery` fiber (run at boot as a module `LifecycleProvider`) runs every `recovery-interval` and repairs exactly two situations: events a subscribed consumer never got a ledger row for (consumer registered after publish, or publish raced a deploy), and `Pending` rows whose scheduler task vanished (e.g. the scheduler deleted an undecodable task row). Pending rows for a consumer that is no longer registered are logged and left alone so a removed module can drain gracefully after a rollback or a deliberate decommission.

All repair actions are safe to race: opening a ledger row is `ON CONFLICT DO NOTHING`, task scheduling is an upsert that leaves running rows alone, and the terminal-status guard makes a duplicate task a no-op. Several nodes running recovery concurrently just do redundant reads.

## Versioning an event

The decision rule first: **if old payloads still decode into the new class, you don't bump.** A new `Option` field, a field with a sensible default, a hand-written tolerant decoder — that's the "additive within `.v1`" path, and it's a normal code change. Bump to `.v2` only when old payloads *can't* decode, or would decode into something that lies about what happened.

A bump looks like this. The frozen shape takes the version suffix; the current shape keeps the good name, so suffixes accrete on dead code, not live code. The wire strings being explicit is what makes the rename safe (nothing about delivery keys off the class name), and the aggregate identity stays the same across versions:

```scala
// frozen — exists only to decode history
final case class UserAccountDeletedV1(userId: UserId) derives EventCodec
object UserAccountDeletedV1 {
  given DomainEventDescriptor[UserAccountDeletedV1] =
    DomainEventDescriptor("user-account-deleted.v1", "user", _.userId)
}

// current — what publishers publish
final case class UserAccountDeleted(userId: UserId, deletedAt: Instant) derives EventCodec
object UserAccountDeleted {
  given DomainEventDescriptor[UserAccountDeleted] =
    DomainEventDescriptor("user-account-deleted.v2", "user", _.userId)
}
```

Each consumer then holds **two subscriptions with one handler**, upcasting at the edge so knowledge of the v1 shape is quarantined to one adapter line — the domain handler only ever sees the current class:

```scala
override abstract def outboxSubscriptions: List[OutboxSubscription] =
  super.outboxSubscriptions :+
    OutboxSubscription[UserAccountDeleted]("auction")(cleanup.onAccountDeleted) :+
    OutboxSubscription.withDelivery[UserAccountDeletedV1]("auction")((e, d) =>
      cleanup.onAccountDeleted(UserAccountDeleted(e.userId, d.occurredAt)))
```

**Roll out in two releases, subscriptions first.** Release 1 adds the v2 class and the v2 subscriptions everywhere while the publisher still emits v1; release 2 switches the publisher. The reason is a rolling-deploy race: a v2 delivery task can be picked by a still-old node whose subscription registry doesn't know v2, which instantly dead-letters it with `no subscription for user-account-deleted.v2` — recoverable by redrive, but avoidable by ordering. Consumers before producers, the classic expand/contract rule, here enforced by a concrete failure mode.

Two things to keep in view afterwards:

- **A consumer left on v1-only goes silent, not loud.** Fan-out creates deliveries only for consumers subscribed to the published type, and recovery repairs only *subscribed* types — so missing the v2 subscription in one module means that consumer simply never hears about v2 events. No dead-letter, no metric. The bump PR must touch every consumer of the type.
- **The v1 subscription is effectively forever, not transitional.** Full-history replay means a consumer added later meets v1 events; drop the v1 subscription (or the frozen class) only if you also seed those events as `Completed` for it (see [New consumers replay history](#new-consumers-replay-history)). Dropping it while v1 deliveries are still pending dead-letters them.

## Retention

- **`domain_event` is forever, by design** — it is the audit log, and `event_type` is a versioned contract (additive changes within `.v1`, breaking changes bump to `.v2` — see [Versioning an event](#versioning-an-event)). The corollary: **payloads must not contain data you may be obliged to erase.** `UserAccountDeleted(userId)` carries only the id — keep it that way; if an event needs personal data, reference it, don't embed it (or you're into crypto-shredding territory).
- **`outbox_delivery` is the dedup memory, not a scratch table.** A `Completed` row is the only thing telling recovery not to re-deliver that event. Never prune the ledger alone while the events remain and the consumer still subscribes to the type — recovery would faithfully re-deliver all of it. If unbounded growth becomes a problem, prune *event and ledger rows together* (ledger first — it has the FK) for events older than your horizon whose deliveries are all terminal; accept that replay-from-history stops working past that horizon.

## Observability

- **Per-attempt traces and metrics ride the scheduler.** Every delivery attempt is a scheduler task, so it runs in a `scheduler.execute outbox-deliver:<consumer>` span (`task.instance` holds the event id; attempt count and recorded exception alongside) and shows up in `scheduler.executions{task.name="outbox-deliver:<consumer>"}` alongside `scheduler.in_flight` and `scheduler.revived`.
- **Terminal outcomes**: `outbox.deliveries{consumer, outcome="completed"|"dropped"|"exhausted"}`, counted once per terminal transition, after the transaction commits — rolled-back `Retry` attempts don't count.
- **Recovery repairs**: `outbox.recovered{case="missing_ledger"|"stranded_task"}`, plus an info log per non-empty pass. A *steadily* nonzero rate in a healthy system means something upstream is wrong (crashing deploys, deleted tasks) — recovery healing constantly is a symptom, not a feature.
- **Traces do not cross the async hop — deliberately.** The publishing request's trace ends at commit; each delivery runs in its own trace, like every other scheduler task in this template. Correlate by event id: it's the delivery span's `task.payload` attribute and in every outbox log line. Propagating a `traceparent` through `domain_event` and linking producer/consumer spans (the OTel messaging conventions) needs a column on the event table — if you want it, add it before your first release, not after.

## Configuration

```hocon
outbox {
  recovery-interval    = 5m    # recovery sweep cadence (also the redrive/backfill cadence)
  recovery-batch-size  = 100   # repaired events per consumer per sweep
  delivery-max-retries = 10    # per-delivery attempts before dead-lettering; remove to retry forever
}
```

Each key has an `OUTBOX_*` environment override (`application.conf`) — with one asymmetry: an env var can set `delivery-max-retries` but never *unset* it, so "retry forever" requires deleting the key from the conf.

Do the retry math before tuning `delivery-max-retries`: under the scheduler's default backoff (30s base, ×1.5, capped at 1h) the ten retry delays sum to ~57 minutes, so a permanently failing delivery dead-letters after **~1 hour and 11 attempts**; past attempt 10 the cap dominates, so each additional retry buys roughly one more hour (e.g. `20` ≈ an overnight-outage budget of ~10h). Detection doesn't wait for exhaustion either way — `scheduler.executions{outcome="failure"}` fires on every attempt, and a premature dead-letter costs exactly one redrive `UPDATE`.

## Where to look

- `utils/events/outbox/` — `Outbox` (publish), `OutboxDispatcher` (delivery), `OutboxRecovery`, repositories; ~400 lines total, built to be read.
- The worked example: `DELETE /v1/users/me` → `UserService.deleteAccount` publishes `UserAccountDeleted`; the `auth` consumer (`AuthAccountCleanupService`) revokes provider identities and refresh tokens, and the `auction` consumer (`AuctionAccountCleanupService`) cancels open auctions, pushes the live event, and mails bidders. The auth side (anonymization, token revocation, the residual access-token window, and the industry-standard extensions this template deliberately leaves out) is documented in [auth.md](auth.md).
- Tests: `OutboxSpec` (publish atomicity), `OutboxDispatcherSpec` (the Reaction contract), `OutboxEndToEndSpec` (through a live scheduler), `OutboxRecoverySpec`, `SchedulerOnAbandonSpec` (dead-letter mechanics), `UserServiceSpec` / `AuthAccountCleanupServiceSpec` / `AuctionAccountCleanupSpec` (the worked example).
