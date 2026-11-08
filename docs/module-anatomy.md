# Module anatomy

A module is a `trait` that mixes in a few `Provider` interfaces, declares the resources it needs as abstract `val`s, and wires up its routers/services/templates with macwire. `ApplicationLoader` extends every concrete module trait, satisfies the abstract dependencies, and gathers the contributions through the providers' `super` chain.

This doc is the reference for what's available. For a hands-on tutorial walking through a new feature end to end, see [adding-a-module.md](adding-a-module.md).

## The shape of a module

```scala
trait AuctionModule
    extends RouteProvider
    with AuthRouteProvider
    with WsRouteProvider
    with RecurringTaskProvider
    with OneTimeTaskProvider
    with MailPreviewProvider {

  // 1. Dependencies the module needs from outside, declared as abstract vals.
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val objectStore: ObjectStore
  val schedulerClient: SchedulerClient
  // …

  // 2. Internal wiring.
  private val auctionRepository      = wire[AuctionRepository]
  private val auctionService         = wire[AuctionService]
  private val auctionRouter          = wire[AuctionRouter]
  // …

  // 3. Provider contributions: how this module participates in each
  //    cross-cutting list (routes, tasks, mail previews).
  override abstract def route(auth: AuthContext): Route =
    super.route(auth) ~ auctionRouter.authedRoutes(auth)

  override abstract def recurringTasks: List[Task[?]] =
    super.recurringTasks :+ auctionService.closeExpiredAuctionsTask

  // …
}
```

Three things to notice:

- **`override abstract`.** Each provider method calls `super.<thing>` and concatenates the module's own contribution. The whole mixin chain in `ApplicationLoader` ends in the `Application…Provider` umbrella trait, which supplies the empty default. You can read every module's body without reading the others.
- **Dependencies are abstract `val`s, not constructor parameters.** This is what makes the trait composable: `ApplicationLoader`'s constructor receives the runtimes once and the `val`s land in scope for every module that mixes in.
- **Internal wiring uses macwire** for non-trivial constructors and plain `new` for the rest. Either is fine; macwire saves typing when there are five-plus constructor params, plain `new` reads better when there are two.

## Providers

A provider is a tiny trait with one or two abstract methods. Modules mix them in to declare what they contribute.

| Provider                  | Contributes                                                          | Module overrides                                |
| ------------------------- | -------------------------------------------------------------------- | ----------------------------------------------- |
| `RouteProvider`           | Public routes (no auth gate)                                         | `def route: Route`                              |
| `AuthRouteProvider`       | Routes that require an authenticated user (`AuthContext`)            | `def route(auth: AuthContext): Route`           |
| `WsRouteProvider`         | Public WebSocket routes                                              | `def wsRoutes(wsb: WebSocketBuilder2[IO]): Route` |
| `AuthWsRouteProvider`     | Authenticated WebSocket routes                                       | `def wsRoutes(auth: AuthContext, wsb: WebSocketBuilder2[IO]): Route` |
| `RecurringTaskProvider`   | Tasks the scheduler runs on a schedule (cron, fixed delay, …)        | `def recurringTasks: List[Task[?]]`             |
| `OneTimeTaskProvider`     | Tasks scheduled in response to events (analyze, send mail, …)        | `def oneTimeTasks: List[OneTimeTask[?]]`        |
| `CustomTaskProvider`      | Tasks that compute their next run dynamically                        | `def customTasks: List[CustomTask[?]]`          |
| `MailPreviewProvider`     | Renderable email previews for the dev-mode `/admin/mail-previews` UI | `def mailPreviews: List[MailPreview]`           |
| `LifecycleProvider`       | Background processes / startup–shutdown effects that run for the app's lifetime | `def lifecycles: List[Resource[IO, Unit]]`      |

Most providers are grouped under an `Application…` umbrella. `ApplicationRouteProvider` extends the four route providers and supplies `RouteDirectives.reject` as the default. `ApplicationTaskProvider` extends the three task providers and supplies `Nil`. `MailPreviewProvider` and `LifecycleProvider` stand on their own and inline `Nil` as their default. `ApplicationLoader` mixes them all in, so the chain always terminates cleanly even if no module implements a given provider.

A `LifecycleProvider` contribution is a `Resource[IO, Unit]` — acquire at boot, release at shutdown. `Main` runs them (`application.lifecycles.sequence_`) inside its resource scope, after the runtimes are up and before the HTTP server binds. Use it for a module's app-lifetime background work: the outbox recovery loop and the feature-flag cache-invalidation subscription are the two worked examples. Keep them independent of each other; if one needs another to be running first, compose that ordering inside a single `Resource`. Their release shares the 10-second shutdown limit with the scheduler and the database pool ([deployment](deployment.md#graceful-shutdown)), so don't rely on a long release to finish.

Acquisition starts a lifecycle; it does not wait for it to become *ready*. A `.background` contribution forks its fiber and returns immediately, so the server can bind before, say, an event-bus subscription has actually registered. Treat lifecycles as fail-open: the feature-flag invalidation tolerates a brief startup window where a peer update is missed and the cache falls back to its TTL. If a contribution genuinely must be ready before boot proceeds, encode that wait in the `Resource`'s acquire step rather than assuming acquisition implies readiness.

A module mixes in only what it contributes. The auction module mixes in six providers; `HealthCheckModule` mixes in only `RouteProvider` and `LifecycleProvider`, the latter for the readiness check's background loop.

## Dependencies

The convention is to declare module-level dependencies as `val` (or `lazy val`, when initialization order matters) and given-providing context as `given`:

```scala
given telemetryContext: TelemetryContext      // implicit context for logging/tracing
val transactor: Transactor                    // database access
val objectStore: ObjectStore                  // file storage
val schedulerClient: SchedulerClient          // task scheduling
val cacheRuntime: CacheRuntime                // runtime — the module picks one cache from it
lazy val mailer: Mailer                       // shared infrastructure constructed in ApplicationLoader
lazy val httpClient: …                        // ditto
lazy val userRepository: UserRepository       // cross-module access (auction needs users)
```

`ApplicationLoader` either receives these in its constructor (transactor, runtimes, scheduler client) or constructs them once as `lazy val` (mailer, http client, repositories shared across modules). Every concrete value lands in scope for every module trait that declares it abstractly — the compiler enforces that nothing is missing.

If you find yourself adding a dependency to a module that no other module uses, hold it inside the module rather than promoting it to `ApplicationLoader`. Promote when a second consumer appears.

## Cross-module dependencies

Modules can also expose values for other modules to reuse — typically repositories or services that crop up in more than one feature area. The pattern is symmetric to external dependencies:

- **The owning module declares it as a concrete `lazy val`.** `UserModule` exposes `lazy val userRepository: UserRepository = wire[UserRepository]`.
- **Consuming modules redeclare it as an abstract `lazy val`.** `AuctionModule` and `AuthModule` both have `lazy val userRepository: UserRepository`.
- **`ApplicationLoader` mixes both modules in.** Scala's trait linearization makes the concrete definition from `UserModule` satisfy the abstract declaration in the consumers. No constructor parameter, no manual passing, no macwire awareness.

The convention: the module that *owns* a domain concept exposes its repository (and any cross-cutting service) on the trait so other modules can declare it abstractly. Don't reach into `ApplicationLoader` to thread it through.

A few things to keep in mind:

- Use `lazy val` on both sides. Eager `val`s in traits can initialize in the wrong order.
- Match the type exactly. If the owner exposes `lazy val userRepository: UserRepository`, the consumer must declare the same type — not a supertype, not an alias.
- Don't expose internal services (e.g. an `AuctionService` that another module calls into). That's a sign two modules want to be one. Repositories cross module boundaries because they're the data layer; services are the seam where features start to fuse.

It's three small pieces:

1. **Define the trait.** A provider is a small `trait` declaring what a module contributes — usually one method. If it belongs to a category that has several shapes (routes, tasks), put it next to the others (`utils.http.ApplicationRouteProvider` for HTTP-shaped, `utils.task.ApplicationTaskProvider` for task-shaped, …) so the umbrella file has them together. A standalone provider (like `MailPreviewProvider` or `LifecycleProvider`) just lives on its own.

   ```scala
   trait FeatureFlagProvider {
     def featureFlags: List[FeatureFlag]
   }
   ```

2. **Give it a default so the `super.<thing>` chain terminates.** For a category with an umbrella, the umbrella trait extends every provider in the category and supplies the no-op default; `ApplicationLoader` extends the umbrella.

   ```scala
   trait ApplicationFeatureProvider extends FeatureFlagProvider {
     override def featureFlags: List[FeatureFlag] = Nil
   }
   ```

   A standalone provider has no umbrella — it inlines the default on the trait itself, and `ApplicationLoader` mixes the provider in directly.

   ```scala
   trait LifecycleProvider {
     def lifecycles: List[Resource[IO, Unit]] = Nil
   }
   ```

3. **Wire the contributions somewhere they get used.** A provider only matters if something downstream consumes it. For routes that's `routes(wsb)` in `ApplicationLoader`; for tasks it's `scheduler.run(...)` in `Main`; for lifecycles it's `application.lifecycles.sequence_` in `Main`; for mail previews it's the `MailPreviewRouter` constructor. Find or add the consumer that walks the list.

That's the whole pattern. The reason there aren't more providers in the template is that the existing nine (four route, three task, one mail preview, one lifecycle) cover almost every cross-cutting concern a typical web backend has.
