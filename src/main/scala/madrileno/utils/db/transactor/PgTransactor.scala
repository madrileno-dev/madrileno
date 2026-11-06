package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import fs2.Stream
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.*
import skunk.Session.Credentials
import skunk.codec.all.text
import skunk.data.{Identifier, Notification, Type}
import skunk.exception.EofException
import skunk.implicits.*

import scala.concurrent.duration.*

class PgTransactor(
  sessions: Resource[IO, Session[IO]],
  idleInTransactionTimeout: Duration,
  newConnection: Resource[IO, Session[IO]])
    extends Transactor {

  override def inTransaction[A](f: DBInTransaction[A]): IO[A] =
    sessions.use { session =>
      IO.monotonic.flatMap { started =>
        session.transaction
          .use { transaction =>
            f(using session)(using transaction)
          }
          .handleErrorWith {
            case eof: EofException => IO.monotonic.flatMap(now => IO.raiseError(endedWhileIdle(now - started, eof)))
            case other             => IO.raiseError(other)
          }
      }
    }

  private def endedWhileIdle(elapsed: FiniteDuration, eof: EofException): Throwable =
    idleInTransactionTimeout match {
      case timeout: FiniteDuration if elapsed >= timeout => new TransactionEndedWhileIdle(timeout, eof)
      case _                                             => eof
    }

  override def inSession[A](f: DB[A]): IO[A] =
    sessions.use(session => f(using session))

  override def inNewConnection[A](f: DB[A]): IO[A] =
    newConnection.use(session => f(using session))

  override def notify(channel: Identifier, payload: String): IO[Unit] =
    sessions.use(_.unique(PgTransactor.pgNotify)((channel.value, payload))).void

  override def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] =
    sessions.flatMap(_.channel(channel).listenR(maxQueued))
}

object PgTransactor {
  private val void: Codec[Unit] = Codec.simple(_ => "", _ => Right(()), Type.void)

  private val pgNotify: Query[(String, String), Unit] =
    sql"SELECT pg_notify($text, $text)".query(void).withQuerySummary("NOTIFY")

  def resource(pgConfig: PgConfig)(using TracerProvider[IO], MeterProvider[IO]): Resource[IO, PgTransactor] =
    for {
      tracer <- Resource.eval(TracerProvider[IO].get("madrileno.utils.db.transactor"))
      pool   <- builder(pgConfig).pooled(pgConfig.max)
    } yield new PgTransactor(
      bounded(pool, pgConfig.acquireTimeout, tracer),
      pgConfig.idleInTransactionTimeout,
      builder(pgConfig).withReadTimeout(newConnectionReadTimeout).single
    )

  private def bounded(
    sessions: Resource[IO, Session[IO]],
    acquireTimeout: Duration,
    tracer: Tracer[IO]
  ): Resource[IO, Session[IO]] =
    acquireTimeout match {
      case timeout: FiniteDuration =>
        Resource
          .makeFull[IO, (Session[IO], IO[Unit])] { poll =>
            poll(sessions.allocated.timeoutTo(timeout, IO.raiseError(new DatabaseUnavailable(s"No database connection became free within $timeout"))))
          } { case (_, release) => tracer.noopScope(release) }
          .map(_._1)
      case _ => sessions
    }

  private val newConnectionReadTimeout = 10.seconds

  private def builder(pgConfig: PgConfig)(using TracerProvider[IO], MeterProvider[IO]): Session.Builder[IO] =
    Session
      .Builder[IO]
      .withHost(pgConfig.host)
      .withPort(pgConfig.port)
      .withCredentials(Credentials(pgConfig.user, pgConfig.password))
      .withDatabase(pgConfig.database)
      .withDebug(pgConfig.debug)
      .withSSL(pgConfig.ssl match {
        case PgConfigSSL.None    => SSL.None
        case PgConfigSSL.Trusted => SSL.Trusted
        case PgConfigSSL.System  => SSL.System
      })
      .withConnectionParameters(pgConfig.connectionParameters)
      .withCommandCacheSize(pgConfig.commandCache)
      .withQueryCacheSize(pgConfig.queryCache)
      .withParseCacheSize(pgConfig.parseCache)
      .withReadTimeout(pgConfig.readTimeout)
      .withRedactionStrategy(RedactionStrategy.OptIn)
      .withTelemetryConfig(PgTelemetry.config(pgConfig.telemetry))
      .withSocketOptions(Session.DefaultSocketOptions)
}
