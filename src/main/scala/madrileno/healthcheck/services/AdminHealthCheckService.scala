package madrileno.healthcheck.services

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.healthcheck.routers.dto.{AdminHealthCheckDto, DepCheck, DepStatus}
import madrileno.utils.mailer.{MailSenderKind, MailerConfig}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import java.net.{InetSocketAddress, Socket}
import scala.concurrent.duration.*

class AdminHealthCheckService(
  postgresProbe: PostgresProbe,
  mailerConfig: MailerConfig,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {

  def check(): IO[AdminHealthCheckDto] = {
    (postgresProbe.check(within = 5.seconds), smtpCheck).parTupled.map { case (pg, smtp) =>
      val overall = if (List(pg, smtp).forall(dep => dep.status != DepStatus.Down)) DepStatus.Up else DepStatus.Down
      AdminHealthCheckDto(overall, pg, smtp)
    }
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
