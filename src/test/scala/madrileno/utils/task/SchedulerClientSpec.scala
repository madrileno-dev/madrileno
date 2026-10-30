package madrileno.utils.task

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant

class SchedulerClientSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val client = Scheduler(transactor, SchedulerConfig()).client
  private val probe       = Task.oneTime(TaskDescriptor[String]("schedule-if-absent-probe"))(_ => IO.unit)
  private val later       = Instant.parse("2099-01-01T00:00:00Z")

  "SchedulerClient.scheduleIfAbsent" should {
    "insert the tasks that don't exist yet and leave the existing ones as they are" in {
      for {
        _        <- client.schedule(probe.instance("waiting", "first", at = Some(later)))
        inserted <- client.scheduleIfAbsent(List(probe.instance("waiting", "second"), probe.instance("missing", "second")))
        rows     <- client.listTasks
      } yield {
        inserted shouldBe 1
        rows
          .filter(_.taskName == "schedule-if-absent-probe")
          .map(row => (row.taskInstance, row.taskData, row.nextExecution == later))
          .sortBy(_._1) shouldBe List(("missing", Json.fromString("second"), false), ("waiting", Json.fromString("first"), true))
      }
    }

    "do nothing when given no tasks" in {
      client.scheduleIfAbsent(List.empty[Task[String]]).asserting(_ shouldBe 0)
    }
  }
}
