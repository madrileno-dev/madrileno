# Adding a CRUD module, start to finish

A walkthrough: a "product" module with a name, a price, soft-delete and full CRUD, as a complete vertical slice from migration to domain, repository, service, router, module wiring, tests and the generated OpenAPI. `madrileno.auction` is the richest reference module; `madrileno_module("user")` is the simplest.

> **Start from the scaffold:** `./scripts/scaffold-module.scala <Aggregate> <plural>` writes the module trait, domain, repository, service, router, DTO, migration and the domain, repository and router specs, adds `TestData.random<Aggregate>Id()` and wires the module into `ApplicationLoader` (see [`scripts.md`](scripts.md)). The *domain* (opaque id and name, the aggregate with audit fields, `create(...)`, a `rename` behaviour), the *repository* (Row, Table, RowFilter, soft-delete, `update[E]`) and the *specs* are the finished pattern: you add fields and methods, you don't rebuild the shape. The *service* and *router* are stubs (one `find`, one `GET /<plural>/{id}`, marked `// SCAFFOLD-TODO`) that exist so the module compiles; replace them with the real use cases and delete the markers. The scaffold's `val _ = authContext` is a placeholder: thread `authContext.userId` into the service where rules depend on the caller.
>
> **Behaviour on values is non-negotiable.** Domain operations are methods on the aggregate returning `Either[<Rejection>, <Aggregate>]` (state changes) or `<Aggregate>` (pure refinements), never loose functions in the service. Replace the scaffold's `rename` and `RenameRejection` with your aggregate's real transitions (`cancel`, `markPaid`), don't just delete them.

This guide shows what each piece is for and where the sharp edges are; the scaffolded files show the full code.

## The file layout

```
src/main/scala/madrileno/<module>/
  domain/        # aggregates, opaque types, enums, rejections, smart constructors
  repositories/  # Row + RowTable + RowFilter (all private[repositories]) + Repository
  services/      # use cases, commands, results, recurring tasks
  routers/       # http4s-stir routes
  routers/dto/   # request/response DTOs
  gateways/      # upstream HTTP integrations (optional)
  emails/        # transactional email templates (optional)
  <Module>Module.scala
src/main/resources/db/migration/V<next>__<module>.sql
src/test/scala/madrileno/<module>/{domain,repositories,services,routers}/
src/test/scala/madrileno/support/TestData.scala
```

## How to run it while you work

```bash
sbt --client compile
sbt --client "testOnly madrileno.product.*"
sbt --client testFull
sbt --client "scalafmtAll; scalafixAll"
```

`testOnly` with a pattern that matches nothing passes with no tests run, so check the package is lower case. Tests migrate each suite's Testcontainers Postgres themselves: drop the SQL file in `src/main/resources/db/migration/` and it's picked up. The running app doesn't auto-migrate; `./scripts/app.scala start` and `restart` apply pending migrations first, `./scripts/app.scala migrate` applies them alone (see [dev-workflow.md](dev-workflow.md)).

## The order matters

Build bottom-up and compile and test each layer before the next: the migration fixes column names and types; the domain's shape drives the row's, not the reverse; the service is expressed in `DB[A]` repository methods; routes are thin and DTOs mirror commands and results; the router spec comes last because it exercises the whole stack and generates the OpenAPI. Writing the router before the service ends in redoing it.

## Step 1 — Migration

```sql
CREATE TABLE product(
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX product_name_active_idx ON product (name) WHERE deleted_at IS NULL;
```

`NUMERIC` for money, never `FLOAT`; `TIMESTAMPTZ` for all times; a nullable `deleted_at` for soft-delete; partial indexes on `deleted_at IS NULL`, because every query filters deleted rows out anyway. Schema conventions and identifiers: [database.md](database.md).

## Step 2 — Domain, pure

```scala
opaque type Price = BigDecimal
object Price extends Opaque[Price, BigDecimal] {
  override def validate(value: BigDecimal): Either[String, Price] =
    if (value > 0) Right(value) else Left("Price must be positive")
}

enum RepriceRejection {
  case PriceUnchanged
}

final case class Product(id: ProductId, name: ProductName, price: Price, createdAt: Instant, updatedAt: Instant, deletedAt: Option[Instant]) {
  def reprice(newPrice: Price, now: Instant): Either[RepriceRejection, Product] =
    if (newPrice == price) Left(RepriceRejection.PriceUnchanged)
    else Right(copy(price = newPrice, updatedAt = now))
}
```

- Every distinct value is an **opaque type with `validate`**. kebs derives a Circe `Decoder` that calls `validate`, so `{"price": 0}` is a 400 at the HTTP boundary with no check in the router. `Price(0)` in Scala code throws.
- **Operations are methods on the aggregate** that bump `updatedAt` themselves, so callers can't forget. Each operation has its own rejection enum, even with one case.
- An invariant that needs the current time ("ends in the future") belongs in a `Product.create(..., now)` smart constructor on the companion.

Write `ProductSpec` (domain) next: every `validate` and every rejection case, no database. Idioms: [domain-modeling.md](domain-modeling.md).

## Step 3 — Repository

The scaffolded repository is the pattern; extend it with your columns and queries. What to keep:

- **`Row` is separate from the aggregate and stays inside the package.** Chimney does the round trip. `Row`, `RowTable`, `RowFilter` and `Row.apply` are `private[repositories]` (package-private so the repository spec can reach a row when it must); public methods take and return domain types only. Today the two types carry the same fields; the split keeps an `AuctionRow` from landing where a service expects an `Auction` and lets schema and domain evolve apart.
- **No `Clock[IO]`.** Mutating methods take `now: Instant` from the service, the only clock reader.
- **Public queries take typed arguments and build the filter inside**, so a caller can't switch off the soft-delete default:

  ```scala
  def list(name: Option[ProductName]): DB[List[Product]] =
    repository.findByFilter(ProductRowFilter(name = name.fold(p.any[ProductName])(p.equal))).map(_.map(_.toProduct))
  ```

  Make `deletedAt = p.isNull` the filter's default once it backs a list. Predicates, `Lock`, `insertIfAbsent` and hand-written queries: [database.md](database.md).
- **`find` returns `Option`, `list` returns `List`** (a paginated `list` returns `(List, Long)`).
- **Changes go through `update(id, f)`, never find, modify, save.** It reads the row `FOR UPDATE`, applies the aggregate method and persists in one transaction; the persisting overload is `private`, so the lost-update version can't be written. Its `Option[Either[E, A]]` separates not found from rejected. Locking without modifying (serializing across two tables) uses `findForUpdate`; see `AuctionRepository`.

`ProductRepositorySpec` extends `AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor` and wraps each test in `withRollback { ... }`: save and find, list, soft-delete hides rows, the round trip keeps every field. Assert on the domain types the public methods return.

## Step 4 — Service

Replace the scaffold's `find` with one method per use case, each taking a command and returning a result ADT:

```scala
class ProductService(productRepository: ProductRepository, transactor: Transactor)(using TelemetryContext, UUIDGen[IO], Clock[IO])
    extends LoggingSupport {

  def createProduct(command: CreateProductCommand): IO[Product] =
    transactor.inSession {
      for {
        id  <- IdGenerator.generateId(ProductId)
        now <- Clock[IO].realTimeInstant
        product = Product(id, command.name, command.price, createdAt = now, updatedAt = now, deletedAt = None)
        _ <- productRepository.save(product)
      } yield product
    }

  def getProduct(id: ProductId): IO[Option[Product]] =
    transactor.inSession(productRepository.find(id))

  def repriceProduct(command: RepriceProductCommand): IO[RepriceProductResult] =
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        productRepository.update(command.id, _.reprice(command.newPrice, now)).map {
          case None                                        => RepriceProductResult.NotFound
          case Some(Left(RepriceRejection.PriceUnchanged)) => RepriceProductResult.PriceUnchanged
          case Some(Right(_))                              => RepriceProductResult.Repriced
        }
      }
    }

  def deleteProduct(command: DeleteProductCommand): IO[DeleteProductResult] =
    transactor.inTransaction {
      (for {
        _   <- productRepository.find(command.id).valueOr[DeleteProductResult](DeleteProductResult.NotFound)
        now <- Clock[IO].realTimeInstant.seal
        _   <- productRepository.softDelete(command.id, now).seal
      } yield DeleteProductResult.Deleted).run
    }
}

final case class CreateProductCommand(name: ProductName, price: Price)
final case class RepriceProductCommand(id: ProductId, newPrice: Price)
final case class DeleteProductCommand(id: ProductId)

enum RepriceProductResult { case Repriced, NotFound, PriceUnchanged }
enum DeleteProductResult { case Deleted, NotFound }
```

- **`inSession` for reads and single writes, `inTransaction` when a write depends on a read.** Every transaction is a real Postgres transaction; don't reach for it by reflex.
- **One clock read per flow**, passed down.
- **Sealed monad for branching flows** instead of nested matches ([sealed-monad.md](sealed-monad.md)).
- **Commands and results live at the bottom of the service file**; they're its API.
- `UUIDGen[IO]` and `Clock[IO]` are already in scope in the module. `IdGenerator.generateId` needs both because ids are time-ordered UUIDv7 ([database.md](database.md#identifiers)).

`ProductServiceSpec` uses `TestTransactor` (plus `TestMailpit` if it sends mail) and covers every result case. It can't use `withRollback`: see the sharp edges below.

## Step 5 — DTOs and router

```scala
final case class ProductDto(id: ProductId, name: ProductName, price: Price) derives Encoder.AsObject, Decoder

object ProductDto {
  def apply(product: Product): ProductDto = {
    import io.scalaland.chimney.dsl.*
    product.into[ProductDto].transform
  }
}

final case class RepriceProductRequest(price: Price) derives Decoder, Encoder.AsObject
```

DTOs use the opaque domain types directly, which is what turns invalid input into a 400 before the handler runs.

```scala
class ProductRouter(productService: ProductService)(using TelemetryContext) extends BaseRouter {

  val routes: Route =
    (get & path("products" / JavaUUID.as[ProductId]) & pathEndOrSingleSlash) { id =>
      complete {
        productService.getProduct(id).map[ToResponseMarshallable] {
          case Some(product) => Ok -> ProductDto(product)
          case None          => error(NotFound, "product-not-found", "Product not found")
        }
      }
    }

  def authedRoutes(authContext: AuthContext): Route =
    (patch & path("products" / JavaUUID.as[ProductId] / "price") & entity(as[RepriceProductRequest]) & pathEndOrSingleSlash) { (id, request) =>
      complete {
        productService.repriceProduct(RepriceProductCommand(id, request.price)).map[ToResponseMarshallable] {
          case RepriceProductResult.Repriced       => NoContent
          case RepriceProductResult.NotFound       => error(NotFound, "product-not-found", "Product not found")
          case RepriceProductResult.PriceUnchanged => error(Conflict, "price-unchanged", "New price is the same as the current one")
        }
      }
    }
}
```

`routes` is public, `authedRoutes(auth)` needs a valid JWT; the module wires them separately. Map every result to a status in one match: not found 404, a state conflict 409, ownership 403, input shape 400 (mostly at decode time). Error codes and the wire format: [error-handling.md](error-handling.md); directives, query parameters and auth: [http.md](http.md).

**A list that can grow is paginated.** Offset pagination is `& paginated(ProductSortField.CreatedAt)` in the route, a `PageableSqlFilter` row filter and `Page[ProductDto]` out; a high-write feed uses `cursorPaginated`, `KeysetSqlFilter` and `Cursor[A]`. The pieces, the tie-break and the filter-macro caveat are in [http.md](http.md#pagination).

## Step 6 — TestData factories

The scaffold adds `randomProductId()`. Add a factory for the aggregate in `src/test/scala/madrileno/support/TestData.scala`, with a boring default for every parameter, so a test that only cares about the id writes `TestData.product(id = myId)`:

```scala
def product(
  id: ProductId = randomProductId(),
  name: ProductName = ProductName("Sample product"),
  price: Price = Price(BigDecimal(10)),
  createdAt: Instant = Instant.now(),
  updatedAt: Instant = Instant.now(),
  deletedAt: Option[Instant] = None
): Product = Product(id, name, price, createdAt, updatedAt, deletedAt)
```

Ids come from `randomUuid()` (UUIDv7, like production); the `noRandomUuid` lint bans `UUID.randomUUID`.

## Step 7 — Module trait and registration

```scala
trait ProductModule extends RouteProvider with AuthRouteProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor

  private val productRepository = wire[ProductRepository]
  private val productService    = wire[ProductService]
  private val productRouter     = wire[ProductRouter]

  override abstract def route: Route =
    super.route ~ productRouter.routes
  override abstract def route(auth: AuthContext): Route =
    super.route(auth) ~ productRouter.authedRoutes(auth)
}
```

`override abstract def` chains through `super`, so other modules' routes survive. A recurring task, mail previews or an app-lifetime background process add `RecurringTaskProvider`, `MailPreviewProvider` or `LifecycleProvider` (see `AuctionModule` and [module-anatomy.md](module-anatomy.md)). The scaffold adds `with ProductModule` to `ApplicationLoader`; `ModuleWiringSpec` fails if a module trait isn't mixed in.

## Step 7.5 — Upstreams, caches, events and WebSockets

`ApplicationLoader` already builds the shared pieces; a module declares what it needs (`lazy val httpClient`, `val cacheRuntime: CacheRuntime`, `val eventBusRuntime: EventBusRuntime`).

- **Calling an upstream:** a gateway under `<module>/gateways/`, a one-method trait (the test swap point) with a `Live` class that owns its timeout, cache and error handling. Expose it as `protected lazy val` so `TestApplicationLoader` overrides it with a one-line stub. Cache only successful fetches (`flatTap(cache.put)` before `handleErrorWith`) and raise on decode failures, so an outage doesn't poison the cache for the full TTL. [external-apis.md](external-apis.md), [cache.md](cache.md).
- **Publishing events:** an enum in the domain that `derives EventCodec`, a topic from `eventBusRuntime.topic[E](name, maxQueued)`, published after the commit with failures logged and swallowed. Events carry ids, not entities; delivery is live-only and capped at 8000 bytes on Postgres, so durable side effects belong in the outbox or the scheduler. Testing with `subscribeAwait`: [event-bus.md](event-bus.md).
- **Streaming to clients:** a `wsRoutes` method contributed through `WsRouteProvider` or `AuthWsRouteProvider`, projecting events to a `{kind, data}` envelope of DTOs (the `EventCodec` shape is internal), with `droppingBuffer` per connection. Two-way commands: [websockets.md](websockets.md).

## Step 8 — Router spec, also your OpenAPI source

The scaffold's router spec is the pattern: `BaseRouteSpec with TestApplicationLoader`, one `path(...)` per route, one `onRequest(...).respondsWith[T](status, ...)` per status code. Seed state through a repository the spec creates itself (`new ProductRepository`), because the module's are private:

```scala
private val repriced = TestData.randomProductId()

path("/v1/products/{productId}/price")(
  supports(PATCH, description = "Reprice a product", securitySchemes = Seq(bearerScheme), pathParameters = p[ProductId]("productId"), tags = Seq("Products"))(
    onRequest(body = RepriceProductRequest(Price(BigDecimal(99))), security = bearer.apply(validJwt(authContext)), pathParameters = repriced)
      .respondsWith[EmptyBody](NoContent, description = "Repriced")
      .assert { ctx =>
        application.transactor.inSession(productRepository.save(TestData.product(id = repriced))).unsafeRunSync()
        ctx.performRequest(allRoutes)
      },
    onRequest(body = RepriceProductRequest(Price(BigDecimal(99))), security = bearer.apply(validJwt(authContext)), pathParameters = TestData.randomProductId())
      .respondsWith[Error[Unit]](NotFound, description = "Product not found")
      .assert(ctx => ctx.performRequest(allRoutes).body.title shouldBe Some("Product not found"))
  )
)
```

Every `respondsWith` writes one path and status to `target/baklava/openapi/openapi.yml`; `testOnly *ProductRouterSpec` replaces that suite's entries, `testFull` rebuilds the file. Keep router specs to endpoint × status smoke tests and push behaviour cases down to the service spec. More recipes: [testing-guide.md](testing-guide.md).

## Testing layers at a glance

| Spec type | Extends | Scope |
|---|---|---|
| domain | `AnyWordSpec with Matchers` | opaque types, aggregate methods, smart constructors |
| `*RepositorySpec` | `AsyncWordSpec with AsyncIOSpec with TestTransactor` | CRUD, filters, soft-delete, custom queries |
| `*ServiceSpec` | the above, plus `TestMailpit` for mail | use cases, recurring tasks, notifications |
| `*RouterSpec` | `BaseRouteSpec with TestApplicationLoader` | every endpoint × status (the OpenAPI) |

## Where the sharp edges are

**Opaque types validate at the HTTP boundary but not when reading rows.** The Circe decoder goes through `validate`; the Skunk codec (`text.as[ProductName]`) uses `apply`, which throws on invalid input, so a corrupt row blows up at read. Add SQL `CHECK` constraints for critical fields.

**`withRollback` works for repository specs, not service specs.** The service's `transactor.inSession` takes a fresh pooled connection, which can't see the test's uncommitted rows. Service specs commit and rely on the fresh database per suite.

**Baklava evaluates `body`, `pathParameters` and `security` when the spec class is constructed.** Ids can't be computed inside `.assert`; each case needs its own `val` at class level ([baklava#57](https://github.com/theiterators/baklava/issues/57)).

**`testClock.now` is not the wall clock.** With `TestGivens.fixedClock()`, take test-data timestamps from `testClock.now`, not `Instant.now()`, or setup and assertions drift apart.

**`TestData.user()` makes a unique email per call.** Share one `user` across two seeding calls only when the shared foreign key is the point.

**Mailpit keeps messages across the tests of a suite.** Filter by recipient (`m.to.exists(_.address == email)`), not by subject.

**`mailer.sendTransactionally` ties the mail to the business transaction**: it inserts the job row in the current transaction, so a rollback means no mail ([mailer.md](mailer.md)).

**Recurring tasks over many rows lock and commit per row** (`findForUpdate`, each wrapped in `.attempt` and logged), so one bad row doesn't roll back or block the batch ([scheduler.md](scheduler.md)).

## Before you open the PR

Each item is something a reviewer has flagged on this project at least once.

- **Domain:** every meaningful value is an opaque type with `validate`; aggregate methods return `Either[*Rejection, NewState]` and bump `updatedAt`; no derived data on the aggregate (put it on a read view); the smart constructor enforces every temporal invariant (`endsAt > now` *and* `endsAt > startsAt`); rejection enums cover every guard.
- **Repository:** no `Clock[IO]`; `Row`, `RowTable`, `RowFilter`, `Row.apply` are `private[repositories]`; public methods take typed arguments and return domain types; filters default to excluding soft-deleted rows; changes go through `update(id, f)`; `find` / `list` naming; every custom query has a matching index.
- **Service:** `inSession` for reads, `inTransaction` for read-then-write; one clock read per flow; sealed monad for branching flows; recurring tasks commit per row; mail that depends on the commit uses `sendTransactionally`.
- **Router:** public and authed routes split; every result maps to a status; path parameters use `JavaUUID.as[OpaqueId]`; DTOs use opaque types; lists that can grow are paginated.
- **Module wiring:** the right providers; `override abstract def` chains through `super`; the trait is mixed into `ApplicationLoader`.
- **Migration:** `NUMERIC` for money, `TIMESTAMPTZ` for time; `deleted_at` where the domain soft-deletes; foreign keys to existing tables (`REFERENCES "user"(id)`); an index for every service-layer filter, preferably partial and composite (`(status, ends_at) WHERE deleted_at IS NULL`).
- **Tests:** a `TestData` factory for every new aggregate; the domain spec covers every rejection; the repository spec uses `withRollback`, the service spec commits and covers every result; the router spec covers every endpoint × status and creates its own repository; mail assertions filter by recipient.
- **Hygiene:** `scalafmtAll` and `scalafixAll` clean; imports instead of fully qualified names; no `unsafeRunSync` outside tests; `grep -rn SCAFFOLD-TODO src` prints nothing.
