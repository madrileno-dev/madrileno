# Adding a CRUD module, start to finish

A walkthrough. We're going to add a "product" module — products with a name, price, soft-delete, and full CRUD (create, read, list, update, delete). You'll end up with a complete vertical slice: migration → domain → repo → service → router → module wiring → tests → auto-generated OpenAPI.

Assume you've read an existing module (`madrileno.auction` is the best reference). This is the *doing* guide.

> **Skip the boilerplate:** `./scripts/scaffold-module.scala <Aggregate> <plural>` materializes the file layout below — module trait, domain, repository, service, router, DTO, migration, plus domain / repository / router specs — and wires the module into `ApplicationLoader`. See [`scripts.md`](scripts.md) for what it generates and which placeholders it substitutes. The walkthrough here is still useful as a reference for what each piece is and how it fits together.
>
> **What the scaffold ships vs. what you customize:** the *domain* (opaque `<Aggregate>Id` + `<Aggregate>Name`, the aggregate case class with audit fields, a `<Aggregate>.create(...)` smart constructor, and a `rename` behavior method), the *repository* (Row + Table + RowFilter + soft-delete + the auction-style `update[E]` for atomic transitions), and the *tests* are the finished pattern — you add fields/methods, you don't rebuild the shape. The *service* and the *router* are **stubs**: a single `find` method and a single `GET /<plural>/{id}` route, marked with `// SCAFFOLD-TODO`. They exist so the module compiles green out of the box, not as the finished surface. Replace them with the actual use cases (`create`, `update`, `list`, state transitions) your aggregate needs, then delete the SCAFFOLD-TODO comments. When you have ownership rules, thread `authContext.userId` through to the service — the scaffold's `val _ = authContext` is a placeholder, not the convention.
>
> **Behavior-on-values is non-negotiable.** Domain operations are methods on the aggregate that return `Either[<Rejection>, <Aggregate>]` (state changes) or just `<Aggregate>` (pure refinements). They aren't loose functions in the service. **The scaffold ships with a `rename` method + `RenameRejection` enum as a worked example** — replace it with your aggregate's actual transitions (`cancel`, `markPaid`, `changeStatus`), don't just delete it. Step 2 below shows the full pattern.

## Before you start: the file layout

Every module has the same shape. When you're done, yours will too:

```
src/main/scala/madrileno/<module>/
  domain/        # aggregates, opaque types, enums, domain errors, smart constructors
  repositories/  # Row + RowTable + RowFilter (all `private[repositories]`) + Repository
  services/      # use cases, commands, results, recurring tasks
  routers/       # http4s-stir routes
  routers/dto/   # request/response DTOs
  gateways/      # upstream HTTP integrations (optional)
  emails/        # transactional email templates (optional)
  <Module>Module.scala       # wires it all together
src/main/resources/db/migration/V<next>__<module>.sql
src/test/scala/madrileno/<module>/
  domain/        # pure, no DB
  repositories/  # extends TestTransactor
  services/      # extends TestTransactor + TestMailpit
  routers/       # BaseRouteSpec with TestApplicationLoader — generates OpenAPI
src/test/scala/madrileno/support/TestData.scala   # add factories here
```

## How to run it while you work

```bash
sbt --client compile                                         # fast compile loop
sbt --client "testOnly madrileno.product.domain.*"           # single package
sbt --client "testOnly madrileno.product.repositories.ProductRepositorySpec"
sbt --client testFull                                        # full suite (uncached)
sbt --client scalafmtAll                                     # format before commit
sbt --client scalafixAll                                     # lint
```

Migration timing is split:
- **In tests**, `TestTransactor` runs Flyway against each suite's Testcontainers Postgres at container start — drop the SQL file in `src/main/resources/db/migration/` and it's picked up automatically.
- **When running the app**, the app does **not** auto-migrate. Run `sbt --client "runMain madrileno.main.MigrateMain"` against your dev DB after adding a migration (and after `docker compose down -v`). See [dev-workflow.md](dev-workflow.md).

## The order matters

Build bottom-up, compile + test each layer before starting the next. This is what the eight steps below do, but it's worth saying upfront *why*:

1. **Migration** locks in column names and types — every subsequent type reflects them.
2. **Domain** before persistence, because the aggregate's shape drives the row's shape, not the other way around.
3. **Repository** before service, because the service is expressed in terms of `DB[A]` methods.
4. **Service** before router, because routes are thin — they call service methods and map results to status codes.
5. **DTOs + router** come after the service, because DTOs mirror commands/results.
6. **TestData factories** the moment you have a row/aggregate to seed — you'll want them in spec files immediately.
7. **Module trait** next-to-last, wiring everything together and registering with `ApplicationLoader`.
8. **Router spec** last, exercising the whole stack end-to-end and generating OpenAPI.

If you skip ahead (writing the router before the service), you invariably end up redoing it.

## Step 1 — Start with the migration

Put the schema in place first. It forces you to commit to column names and types, which everything else will follow.

`src/main/resources/db/migration/V<next>__product.sql`:

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

A few conventions baked in already:
- `NUMERIC` for money (never `FLOAT`).
- `TIMESTAMPTZ` for all times.
- `deleted_at` nullable for soft-delete.
- Partial index on `deleted_at IS NULL` because every query filters out deleted rows anyway — the partial index is smaller and matches the filter.

## Step 2 — Domain, pure

`src/main/scala/madrileno/product/domain/Product.scala`:

```scala
package madrileno.product.domain

import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type ProductId = UUID
object ProductId extends Opaque[ProductId, UUID]

opaque type ProductName = String
object ProductName extends Opaque[ProductName, String] {
  override def validate(value: String): Either[String, ProductName] =
    if (value.trim.nonEmpty) Right(value.trim) else Left("Name must not be empty")
}

opaque type Price = BigDecimal
object Price extends Opaque[Price, BigDecimal] {
  override def validate(value: BigDecimal): Either[String, Price] =
    if (value > 0) Right(value) else Left("Price must be positive")

  given Ordering[Price] = Ordering[BigDecimal]
}

enum RenameRejection {
  case NameUnchanged
}

enum RepriceRejection {
  case PriceUnchanged
}

final case class Product(
  id: ProductId,
  name: ProductName,
  price: Price,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {

  def rename(newName: ProductName, now: Instant): Either[RenameRejection, Product] =
    if (newName == name) Left(RenameRejection.NameUnchanged)
    else Right(copy(name = newName, updatedAt = now))

  def reprice(newPrice: Price, now: Instant): Either[RepriceRejection, Product] =
    if (newPrice == price) Left(RepriceRejection.PriceUnchanged)
    else Right(copy(price = newPrice, updatedAt = now))
}
```

Why it looks like this:

- Every distinct value gets an **opaque type** with a `validate`. This matters because **kebs derives a Circe `Decoder` that calls `validate` directly**, turning `{"name": ""}` into a proper decoding failure (HTTP 400). Direct construction via `ProductName("")` *does* throw — but only when you call `apply` in Scala code. At the HTTP boundary you're safe.
- **Domain operations are methods on the aggregate**, returning `Either[*Rejection, Product]`. They bump `updatedAt` themselves — callers cannot forget. Each operation gets its own rejection enum, even if it has one case.
- If you had an invariant that touches external state (like "auction window must be in the future"), you'd add a `Product.create(...)` smart constructor on the companion that takes `now: Instant`. For our simple product, a plain `apply` is enough.

Write **`ProductDomainSpec`** next — pure, fast, covers every opaque type's `validate` and every rejection case. Do it before touching the DB.

## Step 3 — Repository

`src/main/scala/madrileno/product/repositories/ProductRepository.scala`:

```scala
package madrileno.product.repositories

import cats.effect.IO
import madrileno.product.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class ProductRow(
  id: ProductId,
  name: ProductName,
  price: Price,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def toProduct: Product = {
    import io.scalaland.chimney.dsl.*
    this.into[Product].transform
  }
}

private[repositories] object ProductRow {
  def apply(product: Product): ProductRow = {
    import io.scalaland.chimney.dsl.*
    product.into[ProductRow].transform
  }
}

private[repositories] object ProductRowTable
    extends Table[ProductRow]("product")
    with IdTable[ProductRow, ProductId]
    with SoftDeleteTable {
  override val id: Column[ProductId]              = column("id", uuid.as[ProductId])
  val name: Column[ProductName]                   = column("name", text.as[ProductName])
  val price: Column[Price]                        = column("price", numeric.as[Price])
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[ProductRow]) = (id, name, price, createdAt, updatedAt, deletedAt)
}

private[repositories] final case class ProductRowFilter(
  id: SqlPredicate[ProductId] = p.any,
  name: SqlPredicate[ProductName] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (ProductRowTable.id, ProductRowTable.name, ProductRowTable.deletedAt))
}

class ProductRepository {
  def save(product: Product): DB[Unit] =
    repository.create(ProductRow(product)).void

  def find(id: ProductId): DB[Option[Product]] =
    repository.findById(id).map(_.map(_.toProduct))

  def list(name: Option[ProductName]): DB[List[Product]] = {
    val filter = ProductRowFilter(name = name.fold(p.any[ProductName])(p.equal))
    repository.findByFilter(filter).map(_.map(_.toProduct))
  }

  def update[E](id: ProductId, f: Product => Either[E, Product]): DBInTransaction[Option[Either[E, Product]]] =
    repository.findById(id, Lock.ForUpdate).map(_.map(_.toProduct)).flatMap {
      case None          => IO.pure(None)
      case Some(product) =>
        f(product) match {
          case Left(e)        => IO.pure(Some(Left(e)))
          case Right(updated) => persist(updated).as(Some(Right(updated)))
        }
    }

  def softDelete(id: ProductId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  private def persist(product: Product): DB[Unit] =
    repository.update(ProductRow(product))

  private val repository: IdRepository[ProductRow, ProductId]
    & SoftDeleteRepository[ProductRow, ProductId]
    & FilteringRepository[ProductRow, ProductRowFilter] =
    new IdRepository[ProductRow, ProductId](_.id)
      with SoftDeleteRepository[ProductRow, ProductId]
      with FilteringRepository[ProductRow, ProductRowFilter] {
      override val table: ProductRowTable.type = ProductRowTable
    }
}
```

Things to notice:

- **`Row` is a separate type from `Product`, and it stays inside the repo.** Chimney makes the round-trip free. `Row`, `RowTable`, `RowFilter`, and `Row.apply` are all `private[repositories]` — the compiler enforces that they never leak. Public methods take and return domain types only; the conversion happens internally.
- **Why two types if they share fields?** Today `Product` and `ProductRow` carry the same fields — chimney's transform is identity-shaped. The split exists for *boundary discipline*: the compiler tracks where each type lives, so an `AuctionRow` can never accidentally land where a service expects an `Auction`. It also gives the domain and the schema room to evolve independently — a future computed field on `Product`, or a column rename in storage, doesn't force the other side to follow.
- **`private[repositories]` rather than `private`.** Package-private (not class-private) so the repo's own spec — which lives in the same package — can poke `Row` directly when a test really needs to (rare, but the escape hatch exists). Code outside the package can never see it.
- **No `Clock[IO]` at the repo.** `softDelete(id, now)` takes `now` from the caller. The service is the sole clock reader in the stack. This makes repos pure persistence adapters.
- **Public list/find methods get typed signatures, not raw filters.** When users can compose a query (e.g. by name for products, or status + seller for an auction list), the public method takes those exact arguments — `def list(name: Option[ProductName])` — and constructs the `RowFilter` internally. `RowFilter` stays a private mechanism. This keeps the repo API speaking the domain's vocabulary instead of SQL predicates, and means `deletedAt = p.isNull` (the safe-by-default for soft-deleted rows) can never accidentally be turned off by a caller.
- **Naming convention: `find` returns `Option`, `list` returns `List`.** Consistent across the codebase. A method that returns at most one thing is a `find`; a method that returns zero-or-more is a `list`.
- **Modify paths go through `update(id, f)`, not `find → modify → save`.** The callback variant locks the row (`SELECT ... FOR UPDATE`), applies the transformation, and persists in one transaction — there's no possibility of a lost-update race because the unsafe pattern can't be expressed (the persisting overload is `private`). The callback also handles the rejection-vs-not-found distinction in the return type. Read-only lookups still use `find`; locking-without-modifying (e.g. read-side serialization across two tables) uses `findForUpdate` if you need it — see `AuctionRepository` for that pattern.

Write **`ProductRepositorySpec`** with `extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor`. Use `withRollback { ... }` around each test body — it gives you test isolation without truncate. Cover: save + find, list returns saved products, soft-delete hides rows, round-trip preserves all fields. (The spec lives in the same `repositories` package as the repo, so it can still see `private[repositories]` types if you need to assert on the row directly — but prefer asserting on the domain types returned by the public methods.)

## Step 4 — Service

`src/main/scala/madrileno/product/services/ProductService.scala`:

```scala
package madrileno.product.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import madrileno.product.domain.*
import madrileno.product.repositories.ProductRepository
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import pl.iterators.sealedmonad.syntax.*

class ProductService(
  productRepository: ProductRepository,
  transactor: Transactor
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {

  def createProduct(command: CreateProductCommand): IO[Product] = {
    transactor.inSession {
      for {
        id  <- IdGenerator.generateId(ProductId)
        now <- Clock[IO].realTimeInstant
        product = Product(id, command.name, command.price, createdAt = now, updatedAt = now, deletedAt = None)
        _ <- productRepository.save(product)
        _ <- logger.info(s"Created product ${product.id}")
      } yield product
    }
  }

  def getProduct(id: ProductId): IO[Option[Product]] =
    transactor.inSession { productRepository.find(id) }

  def listProducts(name: Option[ProductName]): IO[List[Product]] =
    transactor.inSession { productRepository.list(name) }

  def renameProduct(command: RenameProductCommand): IO[RenameProductResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        productRepository.update(command.id, _.rename(command.newName, now)).map {
          case None                                       => RenameProductResult.NotFound
          case Some(Left(RenameRejection.NameUnchanged))  => RenameProductResult.NameUnchanged
          case Some(Right(_))                             => RenameProductResult.Renamed
        }
      }
    }
  }

  def repriceProduct(command: RepriceProductCommand): IO[RepriceProductResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        productRepository.update(command.id, _.reprice(command.newPrice, now)).map {
          case None                                          => RepriceProductResult.NotFound
          case Some(Left(RepriceRejection.PriceUnchanged))   => RepriceProductResult.PriceUnchanged
          case Some(Right(_))                                => RepriceProductResult.Repriced
        }
      }
    }
  }

  def deleteProduct(command: DeleteProductCommand): IO[DeleteProductResult] = {
    transactor.inTransaction {
      (for {
        _   <- productRepository.find(command.id).valueOr[DeleteProductResult](DeleteProductResult.NotFound)
        now <- Clock[IO].realTimeInstant.seal
        _   <- productRepository.softDelete(command.id, now).seal
      } yield DeleteProductResult.Deleted).run
    }
  }
}

final case class CreateProductCommand(name: ProductName, price: Price)
final case class RenameProductCommand(id: ProductId, newName: ProductName)
final case class RepriceProductCommand(id: ProductId, newPrice: Price)
final case class DeleteProductCommand(id: ProductId)

enum RenameProductResult { case Renamed, NotFound, NameUnchanged }
enum RepriceProductResult { case Repriced, NotFound, PriceUnchanged }
enum DeleteProductResult { case Deleted, NotFound }
```

The shape to notice:

- **`inSession` for reads, `inTransaction` for writes that depend on a prior read**. `createProduct` is a single `INSERT` with no prior read, so `inSession` is fine. `renameProduct` reads the current product, mutates it in-memory via the domain method, and writes back — those three steps should be atomic, hence `inTransaction`. Don't reach for `inTransaction` reflexively — every transaction is a real Postgres transaction with overhead.
- **One clock read per flow**, threaded down. The service is the only place `Clock[IO].realTimeInstant` is called.
- **Sealed-monad for branching writes.** Without it, `renameProduct` would be nested pattern matches. The `.valueOr` / `.left.map` / `.rethrow[IO]` combo keeps the happy path linear.
- **Commands and results live at the bottom of the service file.** They're part of the service's API.

`UUIDGen[IO]` and `Clock[IO]` come from the cats-effect standard library — `UUIDGen[IO]` is provided automatically by cats-effect's implicit `UUIDGen.fromSync[IO]`, and `Clock[IO]` is passed into `ApplicationLoader`'s constructor and is in scope wherever the module trait is mixed in. You don't need to construct either yourself. `IdGenerator.generateId` needs both because it produces **time-ordered UUIDv7** (timestamp from the clock, random bits from `UUIDGen`); see [database.md](database.md#identifiers).

Write **`ProductServiceSpec`** with `TestTransactor` (and `TestMailpit` if the service sends mail). You can't use `withRollback` here — the service opens its own sessions from the transactor pool (a fresh DB connection each time), so data written in your test's outer transaction isn't visible to the service's queries. Each test commits; rely on Testcontainers' fresh DB per suite. Cover every result-enum case.

## Step 5 — DTOs + router

`src/main/scala/madrileno/product/routers/dto/ProductDto.scala`:

```scala
package madrileno.product.routers.dto

import madrileno.product.domain.*
import madrileno.utils.json.JsonProtocol.*

final case class ProductDto(id: ProductId, name: ProductName, price: Price) derives Encoder.AsObject, Decoder

object ProductDto {
  def apply(product: Product): ProductDto = {
    import io.scalaland.chimney.dsl.*
    product.into[ProductDto].transform
  }
}
```

`src/main/scala/madrileno/product/routers/dto/CreateProductRequest.scala`:

```scala
package madrileno.product.routers.dto

import madrileno.product.domain.{Price, ProductName}
import madrileno.utils.json.JsonProtocol.*

final case class CreateProductRequest(name: ProductName, price: Price) derives Decoder, Encoder.AsObject
final case class RenameProductRequest(name: ProductName)                derives Decoder, Encoder.AsObject
final case class RepriceProductRequest(price: Price)                    derives Decoder, Encoder.AsObject
```

Using opaque types directly in request DTOs is intentional: kebs derives a `Decoder` that calls `Opaque.validate` and emits a Circe decoding failure on `Left`. A `{"name": ""}` request becomes a 400 at the HTTP boundary — you don't need to re-validate in the router.

`src/main/scala/madrileno/product/routers/ProductRouter.scala`:

```scala
package madrileno.product.routers

import madrileno.auth.domain.AuthContext
import madrileno.product.domain.*
import madrileno.product.routers.dto.*
import madrileno.product.services.*
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class ProductRouter(productService: ProductService)(using TelemetryContext) extends BaseRouter {

  val routes: Route = {
    (get & path("products") & parameter("name".as[ProductName].?) & pathEndOrSingleSlash) { name =>
      complete {
        productService.listProducts(name).map[ToResponseMarshallable] { products =>
          Ok -> products.map(ProductDto(_))
        }
      }
    } ~
      (get & path("products" / JavaUUID.as[ProductId]) & pathEndOrSingleSlash) { id =>
        complete {
          productService.getProduct(id).map[ToResponseMarshallable] {
            case Some(p) => Ok -> ProductDto(p)
            case None    => error(NotFound, "product-not-found", "Product not found")
          }
        }
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    (post & path("products") & entity(as[CreateProductRequest]) & pathEndOrSingleSlash) { request =>
      complete {
        productService
          .createProduct(CreateProductCommand(request.name, request.price))
          .map[ToResponseMarshallable](p => Created -> ProductDto(p))
      }
    } ~
      (patch & path("products" / JavaUUID.as[ProductId] / "name") & entity(as[RenameProductRequest]) & pathEndOrSingleSlash) { (id, req) =>
        complete {
          productService.renameProduct(RenameProductCommand(id, req.name)).map[ToResponseMarshallable] {
            case RenameProductResult.Renamed       => NoContent
            case RenameProductResult.NotFound      => error(NotFound, "product-not-found", "Product not found")
            case RenameProductResult.NameUnchanged => error(Conflict, "name-unchanged", "New name is the same as the current one")
          }
        }
      } ~
      (patch & path("products" / JavaUUID.as[ProductId] / "price") & entity(as[RepriceProductRequest]) & pathEndOrSingleSlash) { (id, req) =>
        complete {
          productService.repriceProduct(RepriceProductCommand(id, req.price)).map[ToResponseMarshallable] {
            case RepriceProductResult.Repriced       => NoContent
            case RepriceProductResult.NotFound       => error(NotFound, "product-not-found", "Product not found")
            case RepriceProductResult.PriceUnchanged => error(Conflict, "price-unchanged", "New price is the same as the current one")
          }
        }
      } ~
      (delete & path("products" / JavaUUID.as[ProductId]) & pathEndOrSingleSlash) { id =>
        complete {
          productService.deleteProduct(DeleteProductCommand(id)).map[ToResponseMarshallable] {
            case DeleteProductResult.Deleted  => NoContent
            case DeleteProductResult.NotFound => error(NotFound, "product-not-found", "Product not found")
          }
        }
      }
  }
}
```

Two route trees: `routes` is public (list + get), `authedRoutes(auth)` requires a valid JWT (create + update + delete). The module wires them separately (next step). Map every service result to a status code in one match — common mappings:

- not found → 404
- domain invariant violation (state conflict) → 409
- ownership/access → 403
- input shape → 400 (most 400s happen at DTO decode time, before your handler runs)

## Step 5.5 — Pagination, when the list can grow

The `list` route above returns every match — fine for a small table, wrong once it grows. List endpoints in this codebase are offset-paginated; [http.md](http.md) is the full reference, but here's the diff to paginate the product list, layer by layer. (`Page` / `PageRequest` / `SortDirection` live in `madrileno.utils.pagination`; `PageableSqlFilter` in `madrileno.utils.db.dsl`.)

**Domain** — the columns the client may sort by, as an enum (the allow-list — an unknown value is a 400, never a free-form `ORDER BY`):

```scala
enum ProductSortField { case CreatedAt, Name, Price }
```

**Repo** — `ProductRowFilter` mixes in `PageableSqlFilter[ProductSortField]`, gains a `page` field, and implements `pageRequest` / `sortColumnFor` / `tieBreakColumn`:

```scala
private[repositories] final case class ProductRowFilter(
  id: SqlPredicate[ProductId] = p.any,
  name: SqlPredicate[ProductName] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull,
  page: Option[PageRequest[ProductSortField]] = None)
    extends PageableSqlFilter[ProductSortField] {
  override def filterFragment: AppliedFragment =
    fromPredicates((id -> ProductRowTable.id, name -> ProductRowTable.name, deletedAt -> ProductRowTable.deletedAt))
  override protected def pageRequest: Option[PageRequest[ProductSortField]] = page
  override protected def tieBreakColumn: Column[?]                          = ProductRowTable.id
  override protected def sortColumnFor(field: ProductSortField): Column[?] = field match {
    case ProductSortField.CreatedAt => ProductRowTable.createdAt
    case ProductSortField.Name      => ProductRowTable.name
    case ProductSortField.Price     => ProductRowTable.price
  }
}
```

Note that `filterFragment` switches from the `SqlFilterDerivation.filterFragment(this, cols)` macro to `fromPredicates((pred -> col, …))`: the macro derives over the case class's fields and can't see past the extra `page` field. (`fromPredicates` is the lower-level primitive the macro is built on; it's already on `SqlFilter`.) `PageableSqlFilter` appends the primary key — same direction — as a tie-break to whatever sort the client picked, so paging never silently skips or duplicates rows on equal keys.

The repo `list` takes the page and returns `(rows, total)`:

```scala
def list(name: Option[ProductName], page: PageRequest[ProductSortField]): DB[(List[Product], Long)] = {
  val filter = ProductRowFilter(name = name.fold(p.any[ProductName])(p.equal), page = Some(page))
  repository.findPageByFilter(filter).map { case (rows, total) => (rows.map(_.toProduct), total) }
}
```

**Service** — wraps it in `Page[Product]`:

```scala
def listProducts(name: Option[ProductName], page: PageRequest[ProductSortField]): IO[Page[Product]] =
  transactor.inSession(productRepository.list(name, page)).map { case (rows, total) =>
    Page(rows, total, page.limitValue, page.offsetValue)
  }
```

**Router** — add `& paginated(ProductSortField.CreatedAt)` (the `BaseRouter` directive that pulls `?sort-by` / `?sort-dir` / `?limit` / `?offset`, clamping the last two), and map the `Page` through to DTOs:

```scala
(get & path("products") & parameter("name".as[ProductName].?) & paginated(ProductSortField.CreatedAt) & pathEndOrSingleSlash) { (name, page) =>
  complete {
    productService.listProducts(name, page).map[ToResponseMarshallable](result => Ok -> result.map(ProductDto(_)))
  }
}
```

**Specs** — the repo/service list tests now destructure `(rows, total)`; the router spec's `queryParameters` gain `q[Option[ProductSortField]]("sort-by", …)` / `q[Option[SortDirection]]("sort-dir", …)` / `q[Option[Int]]("limit", …)` / `q[Option[Int]]("offset", …)` and `respondsWith[Page[ProductDto]]`.

That's offset pagination — right for a catalogue ("page 4 of 12"). For a high-write feed (a bid stream, an activity log) the right tool is keyset/cursor pagination instead: `KeysetSqlFilter[S, I]` + the `cursorPaginated` directive + the `Cursor[A]` envelope — same idea, no `COUNT(*)`, no skipped rows under concurrent inserts. See [http.md](http.md).

## Step 6 — TestData factories

Spec files seed DB state through a central `TestData` object. Extend it now so repository/service/router specs can use `TestData.product(...)` and `TestData.randomProductId()`.

> If you scaffolded, a `randomProductId()` factory (and an `import madrileno.product.domain.ProductId`) are already in `TestData` — the scaffold injects them. You only add the `product(...)` row factory below (and widen the import to `madrileno.product.domain.*` for the other types it needs).

In `src/test/scala/madrileno/support/TestData.scala`, add to the imports and the object body:

```scala
import madrileno.product.domain.*
// …

// randomUuid() mints time-ordered UUIDv7 ids like production — never raw UUID.randomUUID (the noRandomUuid lint bans it).
def randomProductId(): ProductId = ProductId(randomUuid())

def product(
  id: ProductId = randomProductId(),
  name: ProductName = ProductName("Sample product"),
  price: Price = Price(BigDecimal(10)),
  createdAt: Instant = Instant.now(),
  updatedAt: Instant = Instant.now(),
  deletedAt: Option[Instant] = None
): Product = Product(id, name, price, createdAt, updatedAt, deletedAt)
```

Two conventions baked in:

- **Every parameter has a sensible default**, so a test that only cares about the id writes `TestData.product(id = myId)`.
- **Names and prices are deliberately boring.** If a test needs a specific value (e.g. for an assertion), it overrides. Otherwise the defaults stay out of the way.

## Step 7 — Module trait + registration

`src/main/scala/madrileno/product/ProductModule.scala`:

```scala
package madrileno.product

import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.product.repositories.ProductRepository
import madrileno.product.routers.ProductRouter
import madrileno.product.services.ProductService
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

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

`Clock[IO]` and `UUIDGen[IO]` (required by `ProductService`) resolve implicitly — `ApplicationLoader` takes `val clock: Clock[IO]` in its constructor, and cats-effect's `UUIDGen.fromSync[IO]` given is always in scope. You don't wire them explicitly.

If the module has a recurring task, email previews, or an app-lifetime background process, also extend `RecurringTaskProvider` / `MailPreviewProvider` / `LifecycleProvider` and implement the corresponding `override abstract def`. See `AuctionModule` for the full shape.

Then one line in `ApplicationLoader.scala`:

```scala
class ApplicationLoader(...)
    extends ApplicationRouteProvider
    with ...
    with AuctionModule
    with ProductModule  // <-- add this
    with HealthCheckModule { ... }
```

## Step 7.5 — If your module calls an upstream or needs a cache

Two app-wide dependencies are already constructed by `ApplicationLoader` and available to any module that asks for them:

- `val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]` — shared sttp backend (tracing, metrics, request logging already wired).
- `val cacheRuntime: CacheRuntime` — Transactor-shaped factory. Modules call `cacheRuntime.expiring(ttl, maxSize)` to mint their own typed `Cache[K, V]`.

Gateways live under `<module>/gateways/`. The trait is the swap point (one method ⇒ tests stub it as a one-line SAM lambda); a `Live` class is the production implementation, owning its cache internally so the rest of the module never sees `Cache[...]` in a signature. `VivinoGateway` is the reference — see [external-apis.md](external-apis.md) for the rationale:

```scala
package madrileno.product.gateways

import cats.effect.IO
import madrileno.product.domain.*
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import scala.concurrent.duration.*

trait CatalogGateway {
  def lookup(sku: Sku): IO[Option[CatalogEntry]]
}

class CatalogGatewayLive(
  http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  cacheRuntime: CacheRuntime
)(using TelemetryContext)
    extends CatalogGateway with LoggingSupport {

  private val cache = cacheRuntime.expiring[Sku, Option[CatalogEntry]](expireAfterWrite = 12.hours, maxSize = 10_000)

  override def lookup(sku: Sku): IO[Option[CatalogEntry]] =
    cache.get(sku).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        fetch(sku)
          .timeout(3.seconds)
          .flatTap(result => cache.put(sku, result))
          .handleErrorWith(t => logger.warn(t)(s"Catalog lookup failed for $sku").as(None))
    }

  private def fetch(sku: Sku): IO[Option[CatalogEntry]] = ???
}
```

Two invariants worth keeping:

1. **`flatTap(cache.put)` runs before `handleErrorWith`**, so only successful fetches reach the cache. Failures (network, timeout, parse) raise, skip the `flatTap`, and return `None` uncached — a transient upstream outage must not poison the entry for the full TTL. Legitimate "not found" results (a successful fetch that returned no match) still cache as `None`, which is what you want.
2. **Raise on decode failure** instead of logging-and-returning `None`. `IO.pure(None)` is a successful `IO`, which `flatTap` will happily cache.

In the module, expose the gateway as `protected lazy val` so tests can stub it:

```scala
trait ProductModule extends RouteProvider with AuthRouteProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]

  protected lazy val catalogGateway: CatalogGateway = wire[CatalogGatewayLive]

  private val productRepository = wire[ProductRepository]
  private val productService    = wire[ProductService]
  ...
}
```

And in `TestApplicationLoader`, override with a stub so specs never make real HTTP calls:

```scala
new ApplicationLoader(...) {
  override protected lazy val catalogGateway: CatalogGateway = _ => IO.pure(None)
}
```

### Pub/sub and WebSocket streaming

#### Domain event

Events live in the domain layer and derive `EventCodec` (the project's wrapper over circe `Codec.AsObject`). `EventCodec`'s companion re-exports `JsonProtocol`, so two imports — the type and the givens — give you everything derivation needs:

```scala
package madrileno.product.domain

import madrileno.utils.events.{EventCodec, given}

import java.time.Instant

enum ProductEvent derives EventCodec {
  case Registered(productId: ProductId, name: ProductName, createdAt: Instant)
}
```

#### Bus wiring

`EventBusRuntime` is the Transactor analogue for publish/subscribe. Module declares `val eventBusRuntime: EventBusRuntime` and mints its typed topic — `maxQueued` is the per-subscriber buffer:

```scala
protected lazy val productEventBus: EventBus[ProductEvent] =
  eventBusRuntime.topic[ProductEvent]("product_events", maxQueued = 64)
```

The `name` becomes the Postgres `LISTEN` / `NOTIFY` channel under the Postgres backend — Skunk's identifier rules apply (matches `[A-Za-z_][A-Za-z_0-9$]*`, no dots).

`subscribe` is **live-only** — there's no replay or durability; subscribers receive events that arrive after their subscription registers. Each subscriber has its own bounded buffer (the `maxQueued` above); the bus does not persist.

#### Service: publish after commit

Service publishes after the commit lands. Wrap `publish` so a failure is logged and swallowed — never raised, since the business operation has already committed:

```scala
private def publish(event: ProductEvent): IO[Unit] =
  eventBus.publish(event).handleErrorWith(t => logger.warn(t)(s"Failed to publish $event"))

def register(cmd): IO[RegisterResult] =
  transactor.inTransaction { … }.flatTap {
    case RegisterResult.Registered(p) => publish(ProductEvent.registered(p))
    case _                            => IO.unit
  }
```

Domain events should carry minimal references (ids, timestamps), not whole entities — they cross the wire.

#### WS endpoint: separate `wsRoutes`, separate DTOs

WebSocket endpoints live in a `wsRoutes` method on the router, contributed via the module's `WsRouteProvider` (or `AuthWsRouteProvider` for authed):

```scala
trait WsRouteProvider     { def wsRoutes(wsb: WebSocketBuilder2[IO]): Route }
trait AuthWsRouteProvider { def wsRoutes(auth: AuthContext, wsb: WebSocketBuilder2[IO]): Route }
```

The `WebSocketBuilder2` is the one Ember hands to `withHttpWebSocketApp`, threaded directly through `ApplicationLoader.routes(wsb)` — no getter, no mutable reference.

Don't serialize domain events directly to WS clients: the `EventCodec` JSON shape is your *internal* NOTIFY format, not a public contract. Instead, project to flat per-variant DTOs and wrap with a `{kind, data}` envelope — that's the public contract clients depend on:

```scala
// routers/dto/ProductRegisteredDto.scala
final case class ProductRegisteredDto(productId: ProductId, name: ProductName, createdAt: Instant)
    derives Encoder.AsObject, Decoder

object ProductRegisteredDto {
  def apply(e: ProductEvent.Registered): ProductRegisteredDto = {
    import io.scalaland.chimney.dsl.*
    e.into[ProductRegisteredDto].transform
  }
}

// routers/dto/ProductEventEnvelope.scala
object ProductEventEnvelope {
  def apply(event: ProductEvent): Json = {
    val (kind, data) = event match {
      case e: ProductEvent.Registered => "ProductRegistered" -> ProductRegisteredDto(e).asJson
    }
    Json.obj("kind" -> kind.asJson, "data" -> data)
  }
}
```

The endpoint pipes the bus through `droppingBuffer` (the per-connection bounded queue from `BaseRouter`) so a slow WS client backpressures only its own queue — not the bus's `Topic.publish1`, which would otherwise stall the LISTEN drain for every other subscriber. **Drop semantics are silent**: when the queue is full, new events are discarded with no error frame and no log line, so a slow client just sees gaps in the stream. That's fine for a live ticker (clients reconnect / refetch); reach for a different design if you need lossless delivery.

```scala
def wsRoutes(wsb: WebSocketBuilder2[IO]): Route =
  (get & path("products" / "stream") & pathEndOrSingleSlash) {
    val send = Stream
      .resource(eventBus.subscribeAwait)
      .flatMap(_.droppingBuffer(capacity = 256).map(e => WebSocketFrame.Text(ProductEventEnvelope(e).noSpaces)))
    handleWebSocketMessages(wsb, send, _.drain)
  }
```

`subscribeAwait` (Resource-shaped) waits for the bus to be ready to receive events before yielding the stream — for the Postgres backend that means LISTEN is registered with the database. Plain `subscribe` returns immediately and silently misses events sent during the registration window; use `subscribeAwait` whenever the WS handler is the entry point.

In the module, mix in `WsRouteProvider` (or both, if you have a mix of authed and unauthed WS endpoints) and contribute via the standard `super` chain:

```scala
override abstract def wsRoutes(wsb: WebSocketBuilder2[IO]): Route =
  super.wsRoutes(wsb) ~ productRouter.wsRoutes(wsb)
```

#### Duplex WS: receiving commands from clients

If the WS endpoint should also accept client-sent commands, swap `_.drain` for a real receive pipe and allocate a per-connection response queue. The service stays unchanged — the router parses the incoming frame, calls the existing service method, and pushes responses back to the client. Bypass `handleWebSocketMessages` and call `wsb.build` directly so you can allocate state per connection:

```scala
def wsRoutes(auth: AuthContext, wsb: WebSocketBuilder2[IO]): Route = {
  (get & path("products" / "stream") & pathEndOrSingleSlash) {
    complete {
      for {
        responses <- Queue.bounded[IO, WebSocketFrame](capacity = 64)
        broadcast  = eventBus.subscribe.map(e => WebSocketFrame.Text(ProductEventEnvelope(e).noSpaces))
        outbound   = Stream.fromQueueUnterminated(responses)
        // Apply droppingBuffer to the broadcast only — response frames (acks, validation errors)
        // are low-volume and per-connection; they should not be droppable by a flood of broadcast events.
        send       = broadcast.droppingBuffer(256).merge(outbound)
        receive: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
          case WebSocketFrame.Text(json, _) => handleIncoming(json, auth, responses)
          case _                            => IO.unit
        }
        resp <- wsb.build(send, receive)
      } yield resp
    }
  }
}

private def handleIncoming(json: String, auth: AuthContext, responses: Queue[IO, WebSocketFrame]): IO[Unit] =
  decode[WsCommand](json).fold(
    err => responses.offer(WebSocketFrame.Text(errorFrame(err.getMessage))),
    {
      case WsCommand.Register(reqId, name) =>
        productService
          .register(RegisterCommand(auth.userId, name))
          .flatMap(r => responses.offer(WebSocketFrame.Text(commandResultFrame(reqId, r))))
    }
  )
```

The send stream merges two sources: the broadcast (everyone gets it, fan-out via the bus) and the per-connection response queue (just this client gets it, correlated by `reqId`). `evalMap` on the receive pipe serializes commands per connection — swap to `parEvalMap(n)` if you want concurrent processing.

#### Backend selection

Backend choice is Main's concern: `EventBusRuntime.local` (fs2.Topic, single-process) for dev/tests; `EventBusRuntime.postgres(transactor)` for production. The Postgres variant requires a shared `Supervisor[IO]` and a `TelemetryContext`, and reconnects the LISTEN session on failure. NOTIFY payloads are capped at 8000 bytes by Postgres, messages are transient — not a replacement for the scheduler when you need durability.

#### Testing pubsub from a service spec

In service specs, wire `EventBusRuntime.local` and use `subscribeAwait` for deterministic readiness — the `Resource` registers the subscriber on acquisition, so there's no "did the fiber start pulling yet?" race. Always wrap the test body in `.timeout(...)` so a regression that parks a fiber fails fast instead of hanging the JVM:

```scala
private val eventBus: EventBus[ProductEvent] =
  EventBusRuntime.local.topic[ProductEvent]("product_events_test", maxQueued = 64)

private def withObservedEvent[A](action: IO[A]): IO[(ProductEvent, A)] =
  eventBus.subscribeAwait.use { stream =>
    for {
      subFiber <- stream.take(1).compile.lastOrError.start
      result   <- action
      event    <- subFiber.joinWithNever
    } yield (event, result)
  }.timeout(5.seconds)
```

Use `.subscribeAwait` (returns `Resource[IO, Stream[IO, E]]`) over `.subscribe` whenever ordering with respect to publishes matters — `.subscribe` is for fire-and-forget consumers where the registration race doesn't matter.

## Step 8 — Router spec (Baklava), also your OpenAPI source

Router specs instantiate their own repository so they can seed state directly — they don't reach through `application.*` because those references are `private` inside the module trait. The pattern matches `AuctionRouterSpec`:

`src/test/scala/madrileno/product/routers/ProductRouterSpec.scala`:

```scala
package madrileno.product.routers

import cats.effect.unsafe.implicits.global
import madrileno.auth.domain.AuthContext
import madrileno.product.domain.*
import madrileno.product.repositories.ProductRepository
import madrileno.product.routers.dto.*
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

class ProductRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  // Fresh repository instance — private vals inside ProductModule aren't accessible from here
  private val productRepository = new ProductRepository

  private val authUser      = TestData.user()
  private val authContext   = AuthContext(authUser)
  private val getFoundId    = TestData.randomProductId()
  private val renameSuccess = TestData.randomProductId()
  private val repriceSuccess = TestData.randomProductId()
  private val deleteSuccess = TestData.randomProductId()

  path("/v1/products")(
    supports(
      POST,
      description = "Create a product",
      summary = "Authenticated: creates a new product",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Products")
    )(
      onRequest(
        body = CreateProductRequest(ProductName("Demo"), Price(BigDecimal(42))),
        security = bearer.apply(validJwt(authContext))
      ).respondsWith[ProductDto](Created, description = "Product created")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name shouldBe ProductName("Demo")
        }
    ),
    supports(GET, description = "List products", tags = Seq("Products"))(
      onRequest()
        .respondsWith[List[ProductDto]](Ok, description = "List of products")
        .assert { ctx => ctx.performRequest(allRoutes) }
    )
  )

  path("/v1/products/{productId}")(
    supports(GET, description = "Get a product", pathParameters = p[ProductId]("productId"), tags = Seq("Products"))(
      onRequest(pathParameters = getFoundId)
        .respondsWith[ProductDto](Ok, description = "Product found")
        .assert { ctx =>
          val product = TestData.product(id = getFoundId)
          application.transactor.inSession(productRepository.save(product)).unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe getFoundId
        },
      onRequest(pathParameters = TestData.randomProductId())
        .respondsWith[Error[Unit]](NotFound, description = "Product not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Product not found")
        }
    ),
    supports(
      DELETE,
      description = "Delete a product (soft delete)",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[ProductId]("productId"),
      tags = Seq("Products")
    )(
      onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = deleteSuccess)
        .respondsWith[EmptyBody](NoContent, description = "Deleted")
        .assert { ctx =>
          val product = TestData.product(id = deleteSuccess)
          application.transactor.inSession(productRepository.save(product)).unsafeRunSync()
          ctx.performRequest(allRoutes)
        },
      onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = TestData.randomProductId())
        .respondsWith[Error[Unit]](NotFound, description = "Product not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Product not found")
        }
    )
  )

  path("/v1/products/{productId}/name")(
    supports(
      PATCH,
      description = "Rename a product",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[ProductId]("productId"),
      tags = Seq("Products")
    )(
      onRequest(
        body = RenameProductRequest(ProductName("Renamed")),
        security = bearer.apply(validJwt(authContext)),
        pathParameters = renameSuccess
      ).respondsWith[EmptyBody](NoContent, description = "Renamed")
        .assert { ctx =>
          val product = TestData.product(id = renameSuccess, name = ProductName("Original"))
          application.transactor.inSession(productRepository.save(product)).unsafeRunSync()
          ctx.performRequest(allRoutes)
        },
      onRequest(
        body = RenameProductRequest(ProductName("Whatever")),
        security = bearer.apply(validJwt(authContext)),
        pathParameters = TestData.randomProductId()
      ).respondsWith[Error[Unit]](NotFound, description = "Product not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Product not found")
        }
    )
  )

  path("/v1/products/{productId}/price")(
    supports(
      PATCH,
      description = "Reprice a product",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[ProductId]("productId"),
      tags = Seq("Products")
    )(
      onRequest(
        body = RepriceProductRequest(Price(BigDecimal(99))),
        security = bearer.apply(validJwt(authContext)),
        pathParameters = repriceSuccess
      ).respondsWith[EmptyBody](NoContent, description = "Repriced")
        .assert { ctx =>
          val product = TestData.product(id = repriceSuccess, price = Price(BigDecimal(10)))
          application.transactor.inSession(productRepository.save(product)).unsafeRunSync()
          ctx.performRequest(allRoutes)
        }
    )
  )
}
```

Baklava specs **are** the OpenAPI generator. Every `onRequest(...).respondsWith[T](status, ...)` writes one path + status to `target/baklava/openapi/openapi.yml`. Every test run regenerates the file: `testOnly *ProductRouterSpec` replaces that suite's entries and keeps every other suite's, `test` does the same for whatever suites ran, `testFull` rebuilds everything. (If the file is missing, run `testFull` once — see [dev-workflow.md](dev-workflow.md).)

Keep router specs as "endpoint × status code" smoke tests. If you find yourself writing detailed domain-behavior cases here, push them down to the service spec — Baklava's class-construction binding (see pitfalls) makes heavy parameterization painful.

## Testing layers at a glance

| Spec type           | Extends                                                  | Scope                                             |
|---------------------|----------------------------------------------------------|---------------------------------------------------|
| `*DomainSpec`       | `AnyWordSpec with Matchers`                              | opaque types, aggregate methods, smart constructors |
| `*RepositorySpec`   | `AsyncWordSpec with AsyncIOSpec with TestTransactor`     | CRUD, filters, soft-delete, custom queries         |
| `*ServiceSpec`      | above + `TestMailpit` if mail is involved                | use cases, recurring tasks, notifications          |
| `*RouterSpec`       | `BaseRouteSpec with TestApplicationLoader`               | every endpoint × every status (→ OpenAPI)          |

Run tests as you go — each layer locks in what's below it.

## Where the sharp edges are

**Opaque types validate at the HTTP boundary but not when decoding DB rows.** kebs derives a Circe `Decoder` that routes through `Opaque.validate` and emits a decode failure on `Left` — safe. But the Skunk codec (`text.as[ProductName]`) uses `Opaque.apply`, which throws `IllegalArgumentException` on invalid input. A corrupt DB row can blow up at read. Add SQL `CHECK` constraints for critical fields as defence in depth.

**`withRollback` works for repo specs, not service specs.** The service calls `transactor.inSession { ... }`, which acquires a *fresh* DB connection from the pool. Postgres doesn't share uncommitted state across connections — so data written in your test's outer transaction is invisible to the service's queries. Service specs commit; rely on fresh-DB-per-suite from Testcontainers.

**Baklava's `body`, `pathParameters`, and `security` are all evaluated at class construction time.** You can't compute path IDs inside `.assert` — each test needs its own pre-allocated `val someId = ProductId(...)` at the spec class level. See [theiterators/baklava#57](https://github.com/theiterators/baklava/issues/57). Keep router specs small to avoid accumulating these.

**`testClock.now` is not the wall clock.** If your spec uses `TestGivens.fixedClock()`, test-data timestamps must come from `testClock.now` — using `Instant.now()` for setup and `testClock.now` for assertions creates silent drift.

**`TestData.user()` already generates unique emails per call.** Don't share the same `user` instance across two seeding calls in one test unless the shared FK is deliberate.

**Mailpit persists messages across tests in the same suite.** When asserting on emails, filter by recipient (`m.to.exists(_.address == someUserEmail)`) — subject-substring matching collides across tests sharing names.

**`mailer.sendTransactionally` ties mail delivery to the business transaction.** It inserts a scheduler job row inside the current DB transaction, so if the transaction rolls back, the mail is never sent. That's the key property — not just "no SMTP under lock". Use it whenever the mail is meaningful only if the business operation succeeded.

**Scheduled tasks that process many rows should lock and commit per row.** One batch transaction across N rows means one bad row rolls the rest back. Wrap each `closeOne(...).attempt` and log on failure so a bad row doesn't block the batch.

**Default filter values save you, and `private[repositories]` enforces it.** `deletedAt = p.isNull` on row filters means safe-by-default queries; making `RowFilter` repo-private means a caller can't override that default by accident. When you want to expose filtering to a list endpoint, give the repo method typed args (`status: Option[Status]`, etc.) and translate them into the row filter inside the repo.

## The result

After following all eight steps you should have, for a full CRUD product module:
- 1 migration
- ~7 files in `src/main/scala/madrileno/product/`
- 4 spec files in `src/test/scala/madrileno/product/`
- `TestData` extended with a `product` factory
- 1 line added to `ApplicationLoader`
- Working CRUD endpoints under `/v1/products`
- Auto-generated OpenAPI docs for them
- ~30 tests, all passing
- Zero new `given Clock[IO]` instances at the repo layer, zero raw FQNs, no validation bypasses

If you hit friction that isn't listed above, it's probably a real gap — add it here.

## Before you open the PR

Skim this list. Each item is something a reviewer (human or Copilot) has flagged on this project at least once.

**Domain**
- [ ] Every meaningful value is an opaque type with `validate`
- [ ] Aggregate methods return `Either[*Rejection, NewState]` and bump `updatedAt` themselves
- [ ] No derived data on the aggregate (put it on a read view)
- [ ] Smart constructor enforces all temporal/state invariants (e.g. `endsAt > now` *and* `endsAt > startsAt`)
- [ ] Rejection enums are exhaustive — every branch of every guard has a case

**Repository**
- [ ] No `Clock[IO]` — all mutating methods take `now: Instant`
- [ ] `Row`, `RowTable`, `RowFilter`, `Row.apply` are all `private[repositories]`
- [ ] Public methods take and return domain types — no `Row` or `RowFilter` at the boundary
- [ ] Public list/find methods use typed args (e.g. `status: Option[Status]`); the repo builds the `RowFilter` internally
- [ ] `RowFilter` defaults exclude soft-deleted rows (`deletedAt = p.isNull`)
- [ ] Modify paths go through `update(id, f: Aggregate => Either[E, Aggregate])`; the persisting overload is `private`
- [ ] Naming: `find` returns `Option`, `list` returns `List` (a paginated `list` returns `(List, Long)` — see Step 5.5)
- [ ] Any custom raw-SQL query has a matching index in the migration

**Service**
- [ ] Reads use `transactor.inSession`; writes that combine a read + mutate use `inTransaction`
- [ ] Clock is read exactly once per flow and threaded down
- [ ] Sealed-monad is used for branching write flows instead of nested pattern matches
- [ ] Recurring tasks run per-row transactions with `findForUpdate`; each wrapped in `.attempt` so a bad row doesn't block the batch
- [ ] Notifications use `mailer.sendTransactionally` when the mail should only be sent if the transaction commits

**Router**
- [ ] `routes` (public) and `authedRoutes(auth)` (authed) are split correctly
- [ ] Every service result maps to a specific status code
- [ ] Path params use `JavaUUID.as[OpaqueId]`
- [ ] DTOs derive `Decoder, Encoder.AsObject` and use opaque domain types directly
- [ ] A list endpoint that can grow is paginated — `paginated(...)` + `PageableSqlFilter` + `Page[Dto]`, or `cursorPaginated` + `KeysetSqlFilter` + `Cursor[A]` for a feed (Step 5.5)

**Module wiring**
- [ ] Trait extends the right providers (`RouteProvider`, `AuthRouteProvider`, optionally `RecurringTaskProvider`, `MailPreviewProvider`, `LifecycleProvider`)
- [ ] `override abstract def` chains through `super` so other modules' contributions aren't lost
- [ ] Module trait is mixed into `ApplicationLoader` — `ModuleWiringSpec` will fail in CI if you forget

**Migration**
- [ ] `NUMERIC` for money, `TIMESTAMPTZ` for time
- [ ] Soft-delete columns (`deleted_at TIMESTAMPTZ`) where the domain supports it
- [ ] Foreign keys reference existing tables (e.g. `REFERENCES "user"(id)`)
- [ ] Indexes for every service-layer filter, preferably partial + composite (e.g. `(status, ends_at) WHERE deleted_at IS NULL`)

**Tests**
- [ ] `TestData` has a factory for every new domain type, with sensible defaults
- [ ] Domain spec covers every rejection case
- [ ] Repository spec uses `withRollback` (not service spec — it can't, see sharp edges)
- [ ] Service spec commits (can't roll back) and covers every result-enum case
- [ ] Router spec has at least one case per (endpoint × status code)
- [ ] Router spec instantiates its own repo (`new ProductRepository`) — module-level vals are private
- [ ] Email-dependent tests assert on recipient (`m.to.exists(_.address == ...)`), not subject substrings

**Code hygiene**
- [ ] `sbt --client scalafmtAll` and `sbt --client scalafixAll` run clean
- [ ] FQNs replaced with imports
- [ ] No `Clock` or `UUIDGen` reads at the repo layer
- [ ] No `unsafeRunSync` outside test code
