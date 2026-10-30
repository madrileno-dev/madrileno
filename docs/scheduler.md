# Scheduler

The scheduler is a Postgres-backed task queue that runs alongside the application. It picks tasks off a table, runs them with a per-task heartbeat, retries on failure with exponential backoff, and cleans up after itself.

The design is inspired by [kagkarlsson/db-scheduler](https://github.com/kagkarlsson/db-scheduler) — same shape (single table, FOR UPDATE SKIP LOCKED for picking, optimistic-versioned updates, heartbeat + dead-execution recovery), reimplemented in Scala on top of skunk and cats-effect. If you've used db-scheduler in a JVM project, the mental model carries over.

It does three jobs:

- **Recurring** — run something on a schedule (cron, fixed rate, fixed delay).
- **One-time** — run something once in response to an event ("analyze this image", "send this email").
- **Custom** — run on a dynamic schedule the task itself decides.

Everything goes through the same loop, the same retry policy, and the same admin UI.

## The model

```
                              ┌────────────────────────────────────────────┐
                              │  scheduled_task table (Postgres)           │
                              │   one row per scheduled task instance      │
                              │   stores: payload, schedule, scheduledAt,  │
                              │           heartbeat, version, failures     │
                              └────────────────────────────────────────────┘
                                              │
                                              │ pickAndMark (skip-locked)
                                              ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  Scheduler.run(...)  ←  registered tasks come from oneTimeTasks    │
   │                          / recurringTasks / customTasks providers  │
   │                                                                    │
   │  loops:                                                            │
   │    pollLoop          - reserve permits, pick due rows, dispatch    │
   │    heartbeatLoop     - per running task, every heartbeatInterval   │
   │    deadExecutionLoop - revive rows whose heartbeat went stale      │
   └────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
                                  task.execution(task) :
                                       success → reschedule or remove
                                       failure → backoff retry
```

The scheduler is just an `IO` running on the same JVM as the application. There's no dedicated worker pool, no separate process. `Main` constructs it from the `transactor` and `SchedulerConfig`, then `scheduler.run(...)` is wired into the resource graph alongside the HTTP server.

## The three task types

Every task type uses the same `Task[A]` underneath. The `A` is the task's payload, encoded to JSON and stored in the row.

```scala
// Recurring: runs on a schedule. Re-registered on every startup if not present.
val closeExpiredAuctionsTask: Task[Unit] =
  Task.recurring("close-expired-auctions", Schedule.Cron(CronExpression.unsafeParse("0 * * ? * *"))) { _ =>
    // runs once per minute at second 0
    for {
      now     <- Clock[IO].realTimeInstant
      expired <- transactor.inSession(auctionRepository.listExpired(now))
      _       <- expired.traverse_ { id => closeOne(id, now).attempt.void }
    } yield ()
  }

// One-time: enqueued in response to an event. Removed after success.
val analyzeImageTask: OneTimeTask[AuctionImageId] =
  Task.oneTime(TaskDescriptor[AuctionImageId]("analyze-auction-image"))(task =>
    analyze(task.payload)
  )

// Custom: caller decides when to run next via the return value.
val checkPriceTask: CustomTask[ProductId] =
  Task.custom(TaskDescriptor[ProductId]("price-check")) { productId =>
    checkPrice(productId).map(nextScheduleAt)
    // returns Schedule.NextAt(at, payload) to reschedule, or () to stop
  }
```

A `OneTimeTask[A]` knows how to be instantiated for a specific payload — `task.instance("instance-id", payload, at = ?, priority = ?)`. The instance id makes scheduling idempotent: the scheduler de-duplicates rows on `(taskName, instance)`.

## Schedules

Recurring tasks use one of:

| Schedule                                              | Behaviour                                              |
| ----------------------------------------------------- | ------------------------------------------------------ |
| `Schedule.RecurringWithFixedRate(every, initialDelay)` | Anchored to the previous `scheduledAt + every`. If a run slips so that target is already past, the next run is rescheduled to `now + every` instead — slips don't cause a backlog of rapid re-runs. |
| `Schedule.RecurringWithFixedDelay(after, initialDelay)`| Anchored to the previous run's completion time. Always exactly `after` apart, regardless of run duration. |
| `Schedule.Cron(expression)`                            | Cron expression (quartz-style: 6 fields, seconds first). Next run is computed by the expression after each completion. |

`fixedRate` is for "do something every minute on the minute"; `fixedDelay` is for "do something at most once a minute, with a gap"; cron is for any non-trivial wallclock pattern. Both fixed-rate and fixed-delay take an optional `initialDelay` so the first run after registration isn't immediate.

One-time tasks default to `Schedule.Once` (run as soon as a permit is available). Pass `at = Some(instant)` to `OneTimeTask.instance(...)` to defer the first run to a specific time — internally that becomes `Schedule.OnceAt(at)`.

## Scheduling tasks

`SchedulerClient` schedules a task in three flavours, mirroring the `DB` / `DBInTransaction` distinction in the database layer, and offers `scheduleIfAbsent` for repairs:

```scala
class SchedulerClient(...) {
  def schedule[A](task: Task[A]): IO[Boolean]                            // own session
  def scheduleInSession[A](task: Task[A]): DB[Boolean]                   // join an existing session
  def scheduleTransactionally[A](task: Task[A]): DBInTransaction[Boolean] // join an existing tx
  def scheduleIfAbsent[A](tasks: List[Task[A]]): IO[Int]                  // insert only the missing ones
}
```

Scheduling a task that already exists replaces its payload and next run unless it is running. `scheduleIfAbsent` never touches an existing task: it inserts the tasks that don't exist yet in one statement and returns how many it inserted. Repair loops use it, like the outbox's recovery of deliveries whose task vanished, so a task waiting for its retry keeps its backoff.

Use `scheduleTransactionally` when the enqueue must be atomic with the surrounding work. The image commit flow is the canonical example:

```scala
transactor.inTransaction {
  for {
    _ <- auctionImageRepository.save(image)
    _ <- schedulerClient.scheduleTransactionally(
           analyzeImageTask.instance(s"analyze-${image.id}", image.id)
         )
  } yield ()
}
```

If the row insert and the task enqueue must succeed together (or fail together), they belong in one `inTransaction`. If they're independent, `schedule` opens its own session.

## How execution works

The poll loop runs forever in the background:

1. **Reserve permits** up to `concurrency` (default 10) from a semaphore — caps how many tasks run in parallel.
2. **Pick rows** with `SELECT … FOR UPDATE SKIP LOCKED LIMIT n` so multiple scheduler instances don't race for the same row.
3. **Mark picked rows** with the scheduler's name and a heartbeat timestamp.
4. **Dispatch** each row to a fiber: start a heartbeat loop in the background, then call `task.execution(task)`.
5. **On success**, reschedule (recurring/custom) or remove (one-time).
6. **On failure**, increment `consecutiveFailures` and schedule a retry at `min(retryBaseDelay * retryBackoffRate^(failures-1), retryMaxDelay)` from now. Default: 30s, 45s, ~67s, …, capped at 1h.
7. **Release the permit** so the next poll can fill it.

Heartbeats keep a row's "this scheduler is working on it" lease alive. The dead-execution loop runs every `heartbeatInterval * 2` and revives any row whose heartbeat is older than `heartbeatInterval * missedHeartbeatLimit` (default 5min × 6 = 30min). Revived tasks go back to the pool to be picked up by some scheduler — this is what makes the scheduler crash-tolerant.

## Priority

Each task has a `priority: Short` (default `Task.DefaultPriority = 0`). When the poll loop picks rows, it orders by `priority DESC, next_execution ASC` — higher-priority due-now rows go first, then by FIFO of due time within the same priority. Use a higher number when a task should jump the queue (user-facing operations) and a lower one for background sweeps that can wait.

```scala
analyzeImageTask.instance(s"analyze-$id", id, priority = 100)
```

## Optimistic versioning

Every row carries a `version: Long`. Every `UPDATE` from the scheduler increments it and includes `AND version = ?` in the predicate, so two writers can't silently clobber each other. If a row's version moved out from under a scheduler — typically because the dead-execution loop revived a row whose heartbeat had gone stale — the original scheduler's `reschedule` / `markFailure` / `remove` returns 0 affected rows and is logged as a version mismatch. The revived task continues from the second scheduler's perspective, and the "lost" original run was assumed dead anyway.

You don't usually have to think about this. It's how the scheduler stays consistent across crashes, network partitions, and process restarts without you instrumenting anything.

## Scheduler name

Each scheduler instance writes its identity into `picked_by` when it leases a row. The default is the JVM hostname (`InetAddress.getLocalHost.getHostName`); override via `scheduler.scheduler-name` in config when running multiple schedulers in containers that share a hostname (e.g. multiple replicas in Kubernetes — set it to the pod name). Useful for the admin UI when you want to know which instance is running a given row.

## Trace link

`trace_parent` stores the W3C `traceparent` of the span that scheduled the row, when there was one. The execution span links to it rather than continuing it, so a task shows up as its own trace with a pointer back to the request. Re-scheduling an unpicked instance replaces the value; retries and recurring runs keep it. See the observability guide for the span layout.

## Updating the payload

A task's success path can return a value of type `Unit | A | Schedule.NextAt[A]`. Returning `Unit` keeps the existing payload; returning `A` updates the payload for the next scheduled run; returning `Schedule.NextAt(at, payload)` (custom tasks only) sets both the next run time and the payload. This is how a recurring task can carry forward state across runs — e.g. a "process new orders since X" task that updates X each run.

```scala
Task.recurring("sync-orders", Schedule.RecurringWithFixedDelay(5.minutes), payload = Instant.EPOCH) { task =>
  for {
    now    <- Clock[IO].realTimeInstant
    _      <- syncOrdersBetween(task.payload, now)
  } yield now    // returned A — becomes the new payload for next run
}
```

## Configuration

```hocon
scheduler {
  concurrency             = 10        # max parallel running tasks per scheduler instance
  polling-interval        = 10s       # how often to look for new work when idle
  heartbeat-interval      = 5m        # how often a running task pings the row
  missed-heartbeat-limit  = 6         # how many missed heartbeats before a row is considered dead
  retry-base-delay        = 30s       # first retry delay after failure
  retry-backoff-rate      = 1.5       # multiplier per attempt
  retry-max-delay         = 1h        # cap on retry delay
  max-retries             = ${?…}     # optional: drop the task after N consecutive failures
}
```

Defaults are for a "modest backend"; a high-throughput app raises `concurrency` and may want a tighter `polling-interval`. A scheduler that hosts a lot of long-running tasks raises `heartbeat-interval` so the heartbeats themselves don't dominate the load.

## Per-task retry caps and abandon hooks

`max-retries` in config is the global default. `OneTimeTask` and `CustomTask` can override it per task type and attach a dead-letter hook:

```scala
OneTimeTask[UUID](
  descriptor = TaskDescriptor[UUID]("outbox-deliver:auction"),
  execution = task => deliver(task),
  maxRetries = Some(10),
  onAbandon = Some(task => markFailed(task.payload))    // Task[A] => DBInTransaction[Unit]
)
```

When retries are exhausted, the scheduler removes the row and runs `onAbandon` **in one transaction** — the version-checked remove and the hook's writes commit atomically, so a task is never dropped without its dead-letter bookkeeping (and never bookkept without being dropped). If the abandon transaction itself fails, the row stays and the drop is retried after the next revival. Both fields are resolved from the registered task definition, never persisted — a deploy that changes them affects already-enqueued rows too. The outbox delivery tasks ([outbox.md](outbox.md)) are the worked example.

## Wiring tasks into a module

The scheduler reads task lists from the application's task providers (`recurringTasks`, `oneTimeTasks`, `customTasks`). A module contributes via `super.<thing> :+ myTask`:

```scala
trait MyModule extends RecurringTaskProvider with OneTimeTaskProvider {
  // …
  override abstract def recurringTasks: List[Task[?]] = super.recurringTasks :+ closeExpiredTask
  override abstract def oneTimeTasks: List[OneTimeTask[?]] = super.oneTimeTasks :+ analyzeImageTask
}
```

`Main` calls `scheduler.run(recurringTasks = …, oneTimeTasks = …, customTasks = …)` once at startup, passing the gathered lists. Each recurring task is `INSERT … ON CONFLICT (task_name, task_instance) DO UPDATE SET priority = EXCLUDED.priority WHERE picked = false`-ed — first startup creates the row, subsequent startups update only the priority of unrunning copies (running rows are left alone, so an in-flight run isn't disturbed). One-time and custom tasks aren't pre-registered — they're inserted whenever a service calls `schedulerClient.schedule(...)` for a specific instance.

## Failure modes the scheduler handles for you

- **Process crash mid-task.** Heartbeats stop. After `missed-heartbeat-limit` heartbeat intervals, another scheduler revives the row. The task runs again from scratch — the assumption is that tasks are idempotent, so retry-from-scratch is the recovery model.
- **Transient task failure.** Caught, logged, scheduled for retry with backoff. After the task's `maxRetries` (falling back to the global `max-retries`, if configured), the row is removed and its `onAbandon` hook runs in the same transaction; without a cap, retries continue indefinitely.
- **Multiple scheduler instances.** `SELECT … FOR UPDATE SKIP LOCKED` ensures only one instance picks any given row. Heartbeat `(scheduler_name, ...)` lets the dead-execution loop tell "alive elsewhere" from "actually dead".
- **A task whose payload no longer parses.** When `pickAndMark` returns rows whose payloads can't be decoded against any registered descriptor, those rows are deleted with an error log — the task type was renamed or removed and the orphaned row would otherwise loop forever.

## Failure modes you have to handle yourself

- **Non-idempotent tasks.** A task that gets retried after a crash mid-run will run twice. Make tasks idempotent. The image variant generator uses `INSERT … ON CONFLICT DO NOTHING` so re-runs don't violate constraints; analyzers check `analyzedAt.isEmpty` first.
- **Payload schema evolution.** Once a task is enqueued, its payload sits in the table as JSON until it's picked up. If you change the payload's schema before old rows drain, decoding fails. Either keep payloads backwards-compatible or drop the old task type and let the orphaned-row cleanup do its job.

## The admin UI

`/admin/jobs` (Basic-Auth gated, GET-only) shows:

- All registered task types (recurring / one-time / custom).
- Recurring tasks with their schedule, next run, last success/failure, and which instance owns the row.
- Currently running rows with their picker and last heartbeat.
- Failing rows (one or more consecutive failures), most recent first.
- Orphaned rows whose `task_name` isn't registered in the running app.

Provided by `SchedulerAdminRouter`. Read-only — no buttons to trigger, retry, or delete; you'd add those routes yourself if you need them. It shares the `/admin` Basic-Auth subtree with the health probes; gate it with a strong `ADMIN_PASSWORD`.

## Testing

`Scheduler(transactor, SchedulerConfig())` against the test transactor gives you a real scheduler client without running the loop. Service specs use `scheduler.client.schedule(...)` to verify their wiring; they don't usually exercise the full execution loop, since the polling intervals make tests slow.

For end-to-end task execution in tests, call `task.execution(task)` directly on a constructed `Task[A]` (see `AuctionImageServiceSpec` for the pattern). This bypasses the scheduler entirely and runs the task body in-process, which is what you typically want — you're testing what the task does, not the scheduler itself.

## Where to look next

- [database.md](database.md) — the `inSession` / `inTransaction` distinction shows up in `scheduleInSession` / `scheduleTransactionally`.
- [error-handling.md](error-handling.md) — how to make tasks idempotent and what to log on transient failures.
- [observability.md](observability.md) — every task execution is wrapped in an OpenTelemetry span with `task.name`, `task.instance`, `task.payload`.
