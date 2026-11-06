package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class JobsReportIntegrationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val config =
    SchedulerConfig(pollingInterval = 100.millis, retryBaseDelay = 100.millis, heartbeatInterval = 100.millis, missedHeartbeatLimit = 3)

  private def report(
    scheduler: Scheduler,
    recurring: List[Task[?]] = Nil,
    oneTime: List[OneTimeTask[?]] = Nil
  ): IO[JobsReport] =
    (scheduler.client.listTasks, Clock[IO].realTimeInstant).mapN { (rows, now) =>
      JobsReport(recurring, oneTime, Nil, rows, scheduler.client.config, now)
    }

  private def hangingIs(health: JobHealth)(built: JobsReport): Boolean =
    built.running.exists(job => job.task == "hanging-import/i-1" && job.health == health)

  private def eventually[A](check: IO[A])(done: A => Boolean): IO[A] =
    (IO.sleep(100.millis) *> check).iterateUntil(done).timeout(15.seconds)

  "The jobs report of a real scheduler" should {
    "call a recurring task that keeps failing Failing while the scheduler retries it" in {
      val scheduler = Scheduler(transactor, config)
      val failing   =
        Task.recurring("always-failing", Schedule.RecurringWithFixedDelay(100.millis))(_ => IO.raiseError(new RuntimeException("upstream down")))
      scheduler.run(recurringTasks = List(failing)).surround {
        eventually(report(scheduler, recurring = List(failing)))(_.recurring.exists(_.health == JobHealth.Failing)).map { built =>
          built.status shouldBe JobsStatus.Unhealthy
          built.failing.map(_.task) should contain("always-failing/recurring")
        }
      }
    }

    "call a one-time task Overdue when no scheduler picks it up" in {
      val scheduler = Scheduler(transactor, config.copy(overdueAfter = 1.minute))
      val mail      = Task.oneTime(TaskDescriptor[Unit]("late-mail"))(_ => IO.unit)
      for {
        now   <- Clock[IO].realTimeInstant
        _     <- scheduler.client.schedule(mail.instance("m-1", (), at = Some(now.minusSeconds(3600))))
        built <- report(scheduler, oneTime = List(mail))
      } yield {
        built.overdue.map(job => job.task -> job.health) shouldBe List("late-mail/m-1" -> JobHealth.Overdue)
        built.status shouldBe JobsStatus.Unhealthy
      }
    }

    "call a task Stalled once the scheduler running it stops sending heartbeats" in {
      val scheduler = Scheduler(transactor, config.copy(missedHeartbeatLimit = 1000))
      val hanging   = Task.oneTime(TaskDescriptor[Unit]("hanging-import"))(_ => IO.never)
      val observed  = Scheduler(transactor, config)
      for {
        _ <- scheduler.client.schedule(hanging.instance("i-1", ()))
        _ <- scheduler.run(oneTimeTasks = List(hanging)).surround {
               eventually(report(observed, oneTime = List(hanging)))(hangingIs(JobHealth.Healthy))
             }
        stalled <- eventually(report(observed, oneTime = List(hanging)))(hangingIs(JobHealth.Stalled))
      } yield stalled.status shouldBe JobsStatus.Unhealthy
    }
  }
}
