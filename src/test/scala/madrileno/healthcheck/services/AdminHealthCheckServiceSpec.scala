package madrileno.healthcheck.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.support.TestTransactor
import madrileno.utils.mailer.{MailSenderKind, MailerConfig}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.ServerSocket

class AdminHealthCheckServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def closedPort: Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def service(sender: MailSenderKind): AdminHealthCheckService = {
    val mailerConfig = MailerConfig(host = "localhost", port = closedPort, fromAddress = "ops@example.com", tls = false, sender = sender)
    new AdminHealthCheckService(new PostgresProbe(new HealthCheckRepository, transactor, Clock[IO]), mailerConfig, Clock[IO])
  }

  "AdminHealthCheckService" should {
    "not probe SMTP when mail goes to the log, so a healthy dev stack reports Up" in {
      service(MailSenderKind.Log).check().map { result =>
        result.smtp.status shouldBe DepStatus.Disabled
        result.postgres.status shouldBe DepStatus.Up
        result.status shouldBe DepStatus.Up
      }
    }

    "report Down when mail goes over SMTP and nothing answers on the SMTP port" in {
      service(MailSenderKind.Smtp).check().map { result =>
        result.smtp.status shouldBe DepStatus.Down
        result.status shouldBe DepStatus.Down
      }
    }
  }
}
