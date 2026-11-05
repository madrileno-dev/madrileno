package madrileno.utils.db.transactor

import cats.effect.{Fiber, IO, Outcome, Resource}
import fs2.Stream
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.*
import skunk.Session.Credentials
import skunk.codec.all.text
import skunk.data.{Identifier, Notification, Type}
import skunk.implicits.*

import scala.concurrent.duration.{Duration, FiniteDuration}

class PgTransactor(sessions: Resource[IO, Session[IO]]) extends Transactor {

  override def inTransaction[A](f: DBInTransaction[A]): IO[A] =
    sessions.use { session =>
      session.transaction.use { transaction =>
        f(using session)(using transaction)
      }
    }

  override def inSession[A](f: DB[A]): IO[A] =
    sessions.use(session => f(using session))

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
      pool   <- pooled(pgConfig)
    } yield new PgTransactor(bounded(pool, pgConfig.acquireTimeout, tracer))

  private type Allocated = (Session[IO], IO[Unit])

  private def bounded(
    sessions: Resource[IO, Session[IO]],
    acquireTimeout: Duration,
    tracer: Tracer[IO]
  ): Resource[IO, Session[IO]] =
    Resource
      .makeFull[IO, Allocated] { poll =>
        sessions.allocated.start.flatMap { waiting =>
          val acquired = acquireTimeout match {
            case timeout: FiniteDuration =>
              waiting.joinWithNever.timeoutTo(
                timeout,
                giveUp(waiting, tracer) *> IO.raiseError(new DatabaseUnavailable(s"No database connection became free within $timeout"))
              )
            case _ => waiting.joinWithNever
          }
          poll(acquired).onCancel(giveUp(waiting, tracer))
        }
      } { case (_, release) => tracer.noopScope(release) }
      .map(_._1)

  private def giveUp(waiting: Fiber[IO, Throwable, Allocated], tracer: Tracer[IO]): IO[Unit] =
    (waiting.cancel *> waiting.join.flatMap {
      case Outcome.Succeeded(allocated) => allocated.flatMap { case (_, release) => tracer.noopScope(release) }
      case _                            => IO.unit
    }).start.void

  private def pooled(pgConfig: PgConfig)(using TracerProvider[IO], MeterProvider[IO]): Resource[IO, Resource[IO, Session[IO]]] =
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
      .pooled(pgConfig.max)
}
