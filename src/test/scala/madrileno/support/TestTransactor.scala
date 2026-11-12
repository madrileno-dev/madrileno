package madrileno.support

import cats.effect.{IO, Resource}
import madrileno.utils.db.transactor.{DB, DBInTransaction, PgConfig, PgTransactor, Transactor}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.{Session, Transaction}

trait TestTransactor extends TestDatabase with BeforeAndAfterAll { self: Suite =>

  given TracerProvider[IO] = TracerProvider.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]

  private val transactorResources = new SuiteResources

  protected lazy val pgConfig: PgConfig = databaseConfig

  def tracedTransactor(provider: TracerProvider[IO]): Resource[IO, PgTransactor] =
    PgTransactor.resource(pgConfig)(using provider, MeterProvider.noop[IO])

  def transactorWith(configure: PgConfig => PgConfig): Resource[IO, PgTransactor] =
    PgTransactor.resource(configure(pgConfig))

  private lazy val pgTransactor: PgTransactor = transactorResources.allocate(PgTransactor.resource(pgConfig))

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

  override protected def afterAll(): Unit =
    try transactorResources.releaseAll()
    finally super.afterAll()
}
