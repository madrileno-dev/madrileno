package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import fs2.Stream
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.*
import skunk.Session.Credentials
import skunk.codec.all.text
import skunk.data.{Identifier, Notification, Type}
import skunk.implicits.*

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

  def resource(pgConfig: PgConfig)(using TracerProvider[IO], MeterProvider[IO]): Resource[IO, PgTransactor] = {
    val pool = Session
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
      .withConnectionParameters(pgConfig.parameters)
      .withCommandCacheSize(pgConfig.commandCache)
      .withQueryCacheSize(pgConfig.queryCache)
      .withParseCacheSize(pgConfig.parseCache)
      .withReadTimeout(pgConfig.readTimeout)
      .withRedactionStrategy(RedactionStrategy.OptIn)
      .withTelemetryConfig(PgTelemetry.config(pgConfig.telemetry))
      .withSocketOptions(Session.DefaultSocketOptions)
      .pooled(pgConfig.max)
    pool.map(sessions => new PgTransactor(sessions))
  }
}
