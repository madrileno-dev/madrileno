package madrileno.healthcheck.services

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.healthcheck.routers.dto.{AdminHealthCheckDto, DepCheck, DepStatus}
import madrileno.utils.mailer.{MailSenderKind, MailerConfig}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import java.net.{InetSocketAddress, Socket}

class AdminHealthCheckService(
  postgresProbe: PostgresProbe,
  mailerConfig: MailerConfig,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {

  def check(): IO[AdminHealthCheckDto] = {
    (postgresCheck, smtpCheck).parTupled.map { case (pg, smtp) =>
      val overall = if (List(pg, smtp).forall(dep => dep.status != DepStatus.Down)) DepStatus.Up else DepStatus.Down
      AdminHealthCheckDto(overall, pg, smtp)
    }
  }

  private def postgresCheck: IO[DepCheck] =
    postgresProbe.check.flatTap { pg =>
      logger.error(s"Postgres health check failed: ${pg.error.getOrElse("no reason given")}").whenA(pg.status == DepStatus.Down)
    }

  private val smtpConnectTimeoutMs = 2000

  private def smtpCheck: IO[DepCheck] = mailerConfig.sender match {
    case MailSenderKind.Log  => IO.pure(DepCheck(DepStatus.Disabled, None, None))
    case MailSenderKind.Smtp =>
      clock
        .timed(IO.blocking {
          val socket = new Socket()
          try socket.connect(new InetSocketAddress(mailerConfig.host, mailerConfig.port), smtpConnectTimeoutMs)
          finally socket.close()
        })
        .map { case (rtt, _) => DepCheck(DepStatus.Up, Some(rtt.toMillis), None) }
        .handleErrorWith { t =>
          logger.error(t)("SMTP health check failed").as(DepCheck(DepStatus.Down, None, Some(t.getMessage)))
        }
  }
}
