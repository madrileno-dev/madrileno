# Architecture

The application has three layers: an entry point that builds the runtime resource graph, a loader that composes feature modules, and modules themselves. Once you've seen one, the rest is just more of the same.

## The shape

```
Main                    boots the JVM, reads config, builds Resources,
                        owns the HTTP server's lifecycle.

ApplicationLoader       extends every concrete Module trait. The result is
                        a single class that knows about every route, every
                        background task, every mail preview in the app.

XxxModule traits        each one mixes in routes, services, tasks, and
                        mail templates for one feature area
                        (auctions, auth, users, health checks, …).
```

Reading order if you're tracing a request: Module first (where the route is defined), `ApplicationLoader` if you want to know how it got plugged in, `Main` if you want to know what's underneath.

## Main

`Main` is one big `Resource[IO, _]` `for`-comprehension. It loads config, starts OpenTelemetry, opens the database connection pool, builds the HTTP client, and constructs every `Runtime` (cache, rate limiter, object store, event bus, circuit breaker). At the end it constructs `ApplicationLoader` with all of those, hands the loader's task lists to the scheduler, runs the loader's lifecycle resources (module background processes), wraps the loader's HTTP routes in middleware (metrics, request size limits, tracing), and binds the result to an Ember server.

What's important about `Main`:

- It's the only place that picks concrete `Runtime` implementations. Production wires `ObjectStoreRuntime.fromConfig(...)`, which picks disk or S3 from `storage.backend`; tests wire `TestObjectStoreRuntime.inMemory`. The rest of the application doesn't know which.
- Every external resource is acquired as a `Resource[IO, _]`. When the server shuts down, everything closes in reverse order automatically — the database pool, the HTTP client, OpenTelemetry exporters.
- It uses macwire (`wire[…]`) for nothing. Wiring lives in `ApplicationLoader` instead, where it can be type-checked by the compiler against the module hierarchy.

You'll rarely need to edit `Main`. Adding a feature usually adds a `Module` trait and updates `ApplicationLoader`'s mixin list.

## ApplicationLoader

`ApplicationLoader` is a class whose constructor takes the things `Main` built — the config source, transactor, object store runtime, and so on — and whose body is `extends … with AuthModule with UserModule with AuctionModule with HealthCheckModule`. That single mixin chain pulls every feature into one place.

Each module declares the resources it needs as abstract `val`s (e.g. `transactor`, `objectStore`, `schedulerClient`). `ApplicationLoader`'s constructor satisfies them. macwire is used inside the modules themselves to assemble services and routers; the loader-level composition is plain trait inheritance so the compiler can see everything.

The loader also owns a few application-level concerns that don't belong to any single module: the http config, the admin authenticator, the route prefix and authentication directives, and the top-level `routes(wsb)` method that gathers all module routes plus admin and (in dev) mail-preview and Swagger-UI routes.

## Modules

A module is a trait. It mixes in one or more `Provider` interfaces — `RouteProvider`, `AuthRouteProvider`, `WsRouteProvider`, `RecurringTaskProvider`, `OneTimeTaskProvider`, `MailPreviewProvider`, `LifecycleProvider` — to declare what it contributes. It declares its abstract dependencies (for example, `val transactor: Transactor`). Inside, it wires its own services, routers, and templates.

The auction module is the worked example. It mixes in route providers, task providers, and the mail-preview provider; it declares dependencies on the transactor, object store, scheduler client, cache, rate limiter, and event bus; and it wires `AuctionService`, `AuctionImageService`, the routers, and the mail templates with macwire.

See [module-anatomy.md](module-anatomy.md) for the full breakdown.

## Provider traits

Providers are tiny: each one is an interface with one or two abstract methods. `RouteProvider` requires `def route: Route`. `OneTimeTaskProvider` requires `def oneTimeTasks: List[OneTimeTask[?]]`. A module overrides only the providers relevant to it.

Each provider also has an `Application…` umbrella trait (`ApplicationRouteProvider`, `ApplicationTaskProvider`) that supplies a default — `RouteDirectives.reject` for routes, `Nil` for task lists. `ApplicationLoader` extends these, so when a module mixes in a `Provider` and contributes via `super.route ~ myRouter.routes`, the chain terminates cleanly even if no other module also implements that provider.

Adding a new provider type is a small change: define the trait, give it an `Application…` umbrella with a default, and start mixing it in.

## Runtimes

Runtimes are the swap points: `CacheRuntime`, `RateLimiterRuntime`, `ObjectStoreRuntime`, `EventBusRuntime`, `CircuitBreakerRuntime`. Each is a small interface with concrete factory methods on its companion object — `local`, `postgres`, `s3`, `disk`, `inMemory`, `default` — that build the underlying implementation.

Modules accept a runtime as an abstract `val` and use only the interface. Tests use `TestXxxRuntime` variants. Production picks whichever factory in `Main`. Replacing Caffeine with Redis is one factory change; the call sites stay still.

## Request flow

```
HTTP request
  → Ember server
  → ServerMiddleware (OpenTelemetry: tracing, request/response headers)
  → EntityLimiter (rejects bodies above http.max-request-size)
  → Metrics middleware (otel4s)
  → routes(wsb): pathPrefix("v1") + auth gate + module routes
                 ⊥ admin routes (basic-auth gate)
                 ⊥ dev-only: mail previews, Swagger UI
  → Module's router → service → repository → DB / object store / external API
  → response
  → middleware unwinds in reverse
```

The same `routes(wsb)` value is also handed to the WebSocket builder, so HTTP and WebSocket share the auth gate, the prefix, the logging, and the tracing.

## Where to look next

- [module-anatomy.md](module-anatomy.md) — what a module's body looks like, which providers exist, when to add one.
- [adding-a-module.md](adding-a-module.md) — vertical slice of a new feature from migration to OpenAPI.
- [http.md](http.md), [database.md](database.md), [scheduler.md](scheduler.md) — the layers a typical module touches.
