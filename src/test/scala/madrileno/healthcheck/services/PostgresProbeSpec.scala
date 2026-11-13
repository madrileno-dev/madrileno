package madrileno.healthcheck.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Resource}
import io.opentelemetry.api.OpenTelemetry
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.support.{DedicatedPostgres, TestTransactor}
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.DockerClientFactory
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.ServerSocket
import scala.concurrent.duration.*

class PostgresProbeSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor with DedicatedPostgres {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def probe(db: Transactor): PostgresProbe = new PostgresProbe(new HealthCheckRepository, db, Clock[IO])

  private def closedPort: Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def postgresPaused: Resource[IO, Unit] = {
    val docker      = DockerClientFactory.instance().client()
    val containerId = this.containerId
    Resource.make(IO.blocking(docker.pauseContainerCmd(containerId).exec()).void)(_ =>
      IO.blocking(docker.unpauseContainerCmd(containerId).exec()).void
    )
  }

  "PostgresProbe" should {
    "report Up with the round trip when Postgres answers" in {
      probe(transactor).check.map { result =>
        result.status shouldBe DepStatus.Up
        result.latencyMs.isDefined shouldBe true
      }
    }

    "report Down with the reason when nothing listens on the database port" in {
      transactorWith(_.copy(port = closedPort)).use(probe(_).check).map { result =>
        result.status shouldBe DepStatus.Down
        result.error.isDefined shouldBe true
      }
    }

    "report Down within its timeout when Postgres stops answering without closing the connection" in {
      transactorWith(identity)
        .use { db =>
          postgresPaused.surround(probe(db).check.timed)
        }
        .map { case (took, result) =>
          result.status shouldBe DepStatus.Down
          took should be < PostgresProbe.timeout + 2.seconds
        }
    }
  }
}
