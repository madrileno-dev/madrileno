package madrileno.support

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import madrileno.utils.db.transactor.{DB, DBInTransaction, DatabaseTime, PgConfig, PgTransactor, Transactor}
import org.scalatest.Suite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.{Session, Transaction}

trait TestTransactor extends TestContainersForAll { self: Suite =>

  override type Containers = PostgreSQLContainer

  override def startContainers(): PostgreSQLContainer = TestPostgres.start()

  given TracerProvider[IO] = TracerProvider.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]

  protected lazy val pgConfig: PgConfig = withContainers { container =>
    PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = container.databaseName,
      password = Some(container.password)
    )
  }

  def tracedTransactor(provider: TracerProvider[IO]): Resource[IO, PgTransactor] =
    PgTransactor.resource(pgConfig)(using provider, MeterProvider.noop[IO])

  def trackedTransactor(databaseTime: DatabaseTime): Resource[IO, PgTransactor] =
    PgTransactor.resource(pgConfig, databaseTime)

  def transactorWith(configure: PgConfig => PgConfig): Resource[IO, PgTransactor] =
    PgTransactor.resource(configure(pgConfig))

  private lazy val pgTransactor: PgTransactor = {
    // Finalizer discarded — pool lifetime is tied to the Testcontainers PG container.
    // Releasing in afterAll causes broken-pipe errors because ScalaTest's afterAll ordering
    // conflicts with TestContainersForAll's container lifecycle.
    PgTransactor.resource(pgConfig).allocated.unsafeRunSync()._1
  }

  lazy val transactor: Transactor = pgTransactor

  def withSession[A](f: DB[A]): IO[A] =
    transactor.inSession(f)

  def withRollback[A](f: DBInTransaction[A]): IO[A] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.transaction.use { tx =>
        given Transaction[IO] = tx
        f.flatTap(_ => tx.rollback)
      }
    }
}
