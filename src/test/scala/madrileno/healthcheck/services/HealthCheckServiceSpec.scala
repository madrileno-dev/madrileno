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

import scala.concurrent.duration.*

class HealthCheckServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def counting(opened: Ref[IO, Int]): Transactor = new Transactor {
    def inTransaction[A](f: DBInTransaction[A]): IO[A]         = transactor.inTransaction(f)
    def inSession[A](f: DB[A]): IO[A]                          = transactor.inSession(f)
    override def inNewConnection[A](f: DB[A]): IO[A]           = opened.update(_ + 1) *> transactor.inNewConnection(f)
    def notify(channel: Identifier, payload: String): IO[Unit] = transactor.notify(channel, payload)
    def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] = transactor.listen(channel, maxQueued)
  }

  private def service(db: Transactor): HealthCheckService =
    new HealthCheckService(AppConfig("madrileno", Environment.Dev, "test"), new PostgresProbe(new HealthCheckRepository, db, Clock[IO]))

  "readiness" should {
    "open one connection for any number of concurrent requests, and again only once the shared answer is older than its reuse window" in {
      Ref[IO].of(0).flatMap { opened =>
        val readiness = service(counting(opened))
        for {
          burst  <- List.fill(200)(readiness.readiness()).parSequence
          inside <- opened.get
          _      <- IO.sleep(HealthCheckService.readinessReusedFor + 100.millis)
          later  <- readiness.readiness()
          after  <- opened.get
        } yield {
          burst.distinct shouldBe List(DepStatus.Up)
          inside shouldBe 1
          later shouldBe DepStatus.Up
          after shouldBe 2
        }
      }
    }
  }
}
