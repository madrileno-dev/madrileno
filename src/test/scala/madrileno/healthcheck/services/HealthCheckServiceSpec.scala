package madrileno.healthcheck.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.main.{AppConfig, Environment}
import madrileno.support.TestTransactor
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.data.{Identifier, Notification}

import java.net.ConnectException
import scala.concurrent.duration.*

class HealthCheckServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def observed(opened: Ref[IO, Int], reachable: Ref[IO, Boolean]): Transactor = new Transactor {
    def inTransaction[A](f: DBInTransaction[A]): IO[A] = transactor.inTransaction(f)
    def inSession[A](f: DB[A]): IO[A]                  = transactor.inSession(f)
    override def inNewConnection[A](f: DB[A]): IO[A]   =
      opened.update(_ + 1) *> reachable.get.ifM(transactor.inNewConnection(f), IO.raiseError(new ConnectException("Connection refused")))
    def notify(channel: Identifier, payload: String): IO[Unit]                                      = transactor.notify(channel, payload)
    def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] = transactor.listen(channel, maxQueued)
  }

  private val readinessConfig = ReadinessConfig(interval = 100.millis)

  private def service(db: Transactor): HealthCheckService =
    new HealthCheckService(
      AppConfig("madrileno", Environment.Dev, "test"),
      new PostgresProbe(new HealthCheckRepository, db, Clock[IO]),
      readinessConfig
    )

  private def becomes(service: HealthCheckService, status: DepStatus): IO[Unit] =
    (IO.sleep(50.millis) *> service.readiness()).iterateUntil(_ == status).timeout(10.seconds).void

  "readiness" should {
    "report Down until the first check has finished" in {
      service(transactor).readiness().map(_ shouldBe DepStatus.Down)
    }

    "serve the last check's result without opening a connection per request" in {
      (Ref[IO].of(0), Ref[IO].of(true)).flatMapN { (opened, reachable) =>
        val readiness = service(observed(opened, reachable))
        readiness.readinessLifecycle.surround {
          for {
            _      <- becomes(readiness, DepStatus.Up)
            before <- opened.get
            burst  <- List.fill(200)(readiness.readiness()).parSequence
            after  <- opened.get
          } yield {
            burst.distinct shouldBe List(DepStatus.Up)
            (after - before) should be <= 1
          }
        }
      }
    }

    "turn Down on its own when Postgres stops answering, and Up again when it answers" in {
      (Ref[IO].of(0), Ref[IO].of(true)).flatMapN { (opened, reachable) =>
        val readiness = service(observed(opened, reachable))
        readiness.readinessLifecycle
          .surround {
            becomes(readiness, DepStatus.Up) *> reachable.set(false) *> becomes(readiness, DepStatus.Down) *>
              reachable.set(true) *> becomes(readiness, DepStatus.Up)
          }
          .as(succeed)
      }
    }

    "report Down once its last Up is too old to trust, as when the checks stopped" in {
      val readiness = service(transactor)
      for {
        _     <- readiness.readinessLifecycle.surround(becomes(readiness, DepStatus.Up))
        _     <- IO.sleep(readinessConfig.validFor)
        stale <- readiness.readiness()
      } yield stale shouldBe DepStatus.Down
    }
  }
}
