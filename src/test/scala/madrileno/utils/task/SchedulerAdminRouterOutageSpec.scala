package madrileno.utils.task

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO, Resource}
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Method, Request, Response, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pl.iterators.stir.server.ToHttpRoutes

import java.net.ServerSocket
import scala.concurrent.duration.*

class SchedulerAdminRouterOutageSpec extends AnyFunSpec with Matchers {

  private given TelemetryContext   = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private given IORuntime          = IORuntime.global
  private given TracerProvider[IO] = TracerProvider.noop[IO]
  private given MeterProvider[IO]  = MeterProvider.noop[IO]
  private given Clock[IO]          = Clock[IO]

  private val nightly  = Task.recurring("nightly-cleanup", Schedule.RecurringWithFixedDelay(1.hour))(_ => IO.unit)
  private val sendMail = Task.oneTime(TaskDescriptor[Unit]("send-mail"))(_ => IO.unit)

  private def unusedPort: Int = Resource.fromAutoCloseable(IO(new ServerSocket(0))).use(socket => IO(socket.getLocalPort)).unsafeRunSync()

  private def jobsWhileDatabaseIsDown(path: String): Response[IO] =
    PgTransactor
      .resource(PgConfig(host = "127.0.0.1", port = unusedPort, user = "postgres", database = "postgres"))
      .use { transactor =>
        val client = new SchedulerClient(new ClientSchedulerRepository(), transactor, SchedulerConfig())
        val router = new SchedulerAdminRouter(List(nightly), List(sendMail), Nil, client, Clock[IO])
        router.routes.toHttpRoutes.orNotFound.run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path)))
      }
      .unsafeRunSync()

  describe("/admin/jobs while the database refuses connections") {
    it("answers 503 with the registered tasks and the database error in the JSON report") {
      val response = jobsWhileDatabaseIsDown("/jobs.json")
      response.status shouldBe Status.ServiceUnavailable
      val report = response.as[JobsReport].unsafeRunSync()
      report.status shouldBe JobsStatus.Unknown
      report.databaseError.getOrElse("") should include("Connection refused")
      report.registered.recurring shouldBe List("nightly-cleanup (every 1 hour (fixed delay))")
      report.registered.oneTime shouldBe List("send-mail")
    }

    it("answers 503 with a page that says the task table couldn't be read and still lists the registered tasks") {
      val response = jobsWhileDatabaseIsDown("/jobs.html")
      response.status shouldBe Status.ServiceUnavailable
      val page = response.bodyText.compile.string.unsafeRunSync()
      page should include("Unknown")
      page should include("Connection refused")
      page should include("nightly-cleanup")
      page should include("send-mail")
      page should not include "No tasks are failing."
    }
  }
}
