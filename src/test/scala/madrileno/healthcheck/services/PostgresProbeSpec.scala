package madrileno.healthcheck.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Resource}
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.support.TestTransactor
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.data.{Identifier, Notification}

import scala.concurrent.duration.*

class PostgresProbeSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def probe(db: Transactor): PostgresProbe = new PostgresProbe(new HealthCheckRepository, db, Clock[IO])

  private val hanging: Transactor = new Transactor {
    def inTransaction[A](f: DBInTransaction[A]): IO[A]                                              = IO.never
    def inSession[A](f: DB[A]): IO[A]                                                               = IO.uncancelable(_ => IO.never)
    def notify(channel: Identifier, payload: String): IO[Unit]                                      = IO.never
    def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] = Resource.eval(IO.never)
  }

  "PostgresProbe" should {
    "report Up with the round trip when Postgres answers" in {
      probe(transactor).check(within = 2.seconds).map { result =>
        result.status shouldBe DepStatus.Up
        result.latencyMs.isDefined shouldBe true
      }
    }

    "report Down on time even when the check cannot be interrupted" in {
      probe(hanging).check(within = 300.millis).timed.map { case (took, result) =>
        result.status shouldBe DepStatus.Down
        took should be < 2.seconds
      }
    }
  }
}
