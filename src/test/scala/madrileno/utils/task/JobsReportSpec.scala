package madrileno.utils.task

import cats.effect.IO
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration.*

class JobsReportSpec extends AnyWordSpec with Matchers {

  private val now    = Instant.parse("2026-11-05T12:00:00Z")
  private val config = SchedulerConfig(heartbeatInterval = 1.minute, missedHeartbeatLimit = 5, overdueAfter = 10.minutes)

  private val nightly  = Task.recurring("nightly-cleanup", Schedule.Cron(CronExpression.unsafeParse("0 0 1 ? * *")))(_ => IO.unit)
  private val sendMail = Task.oneTime(TaskDescriptor[Unit]("send-mail"))(_ => IO.unit)

  private def row(
    name: String,
    instance: String = "recurring",
    nextExecution: Instant = now.plusSeconds(3600),
    picked: Boolean = false,
    lastHeartbeat: Option[Instant] = None,
    consecutiveFailures: Option[Int] = None,
    data: Json = Json.obj()
  ): TaskRow =
    TaskRow(
      taskName = name,
      taskInstance = instance,
      taskData = data,
      nextExecution = nextExecution,
      picked = picked,
      pickedBy = Option.when(picked)("worker-1"),
      lastSuccess = None,
      lastFailure = consecutiveFailures.map(_ => now.minusSeconds(30)),
      consecutiveFailures = consecutiveFailures,
      lastHeartbeat = lastHeartbeat,
      version = 1L,
      priority = 0,
      traceParent = None
    )

  private def report(rows: TaskRow*): JobsReport =
    JobsReport(List(nightly), List(sendMail), Nil, rows.toList, config, now)

  "JobsReport" should {
    "call a registered recurring task with no row Missing" in {
      val built = report()
      built.recurring.map(job => job.name -> job.health) shouldBe List("nightly-cleanup" -> JobHealth.Missing)
      built.status shouldBe JobsStatus.Unhealthy
    }

    "call everything Healthy when rows are on schedule" in {
      val built = report(row("nightly-cleanup"))
      built.recurring.map(_.health) shouldBe List(JobHealth.Healthy)
      built.status shouldBe JobsStatus.Healthy
    }

    "call a row with consecutive failures Failing and list it" in {
      val built = report(row("nightly-cleanup", consecutiveFailures = Some(3)))
      built.recurring.map(_.health) shouldBe List(JobHealth.Failing)
      built.failing.map(job => job.task -> job.consecutiveFailures) shouldBe List("nightly-cleanup/recurring" -> Some(3))
      built.status shouldBe JobsStatus.Unhealthy
    }

    "call a row waiting past overdue-after Overdue, which means nothing is picking rows up" in {
      val built = report(row("send-mail", instance = "m-1", nextExecution = now.minusSeconds(11 * 60)))
      built.overdue.map(job => job.task -> job.health) shouldBe List("send-mail/m-1" -> JobHealth.Overdue)
      built.status shouldBe JobsStatus.Unhealthy
    }

    "leave a row due within overdue-after Healthy" in {
      report(row("nightly-cleanup"), row("send-mail", instance = "m-1", nextExecution = now.minusSeconds(9 * 60))).status shouldBe JobsStatus.Healthy
    }

    "never call a row Overdue when overdue-after is Inf" in {
      val lenient = JobsReport(
        Nil,
        List(sendMail),
        Nil,
        List(row("send-mail", instance = "m-1", nextExecution = now.minusSeconds(86400))),
        config.copy(overdueAfter = Duration.Inf),
        now
      )
      lenient.overdue shouldBe empty
      lenient.status shouldBe JobsStatus.Healthy
    }

    "call a picked row whose heartbeat stopped for the missed-heartbeat limit Stalled" in {
      val built = report(row("nightly-cleanup", picked = true, lastHeartbeat = Some(now.minusSeconds(6 * 60))))
      built.running.map(_.health) shouldBe List(JobHealth.Stalled)
      built.recurring.map(_.health) shouldBe List(JobHealth.Stalled)
      built.status shouldBe JobsStatus.Unhealthy
    }

    "leave a picked row with a recent heartbeat Healthy" in {
      val built = report(row("nightly-cleanup", picked = true, lastHeartbeat = Some(now.minusSeconds(60))))
      built.running.map(_.health) shouldBe List(JobHealth.Healthy)
      built.status shouldBe JobsStatus.Healthy
    }

    "list rows of unregistered tasks as orphaned without calling the jobs unhealthy" in {
      val built = report(row("nightly-cleanup"), row("renamed-task", instance = "x"))
      built.orphaned.map(_.task) shouldBe List("renamed-task/x")
      built.status shouldBe JobsStatus.Healthy
    }

    "list at most 50 failing rows, most recent failure first, and count all of them" in {
      val rows =
        (1 to 60).map(i => row("send-mail", instance = s"m-$i", consecutiveFailures = Some(1)).copy(lastFailure = Some(now.minusSeconds(i.toLong))))
      val built = report(rows*)
      built.failing.size shouldBe 50
      built.failing.head.task shouldBe "send-mail/m-1"
      built.failingTotal shouldBe 60
    }

    "shorten long payloads" in {
      val built = report(row("send-mail", instance = "m-1", data = Json.obj("body" -> Json.fromString("x" * 500)), consecutiveFailures = Some(1)))
      built.failing.head.payload.length shouldBe 118
      built.failing.head.payload should endWith("…")
    }

    "list the registered task types with recurring schedules" in {
      report().registered shouldBe RegisteredJobs(recurring = List("nightly-cleanup (cron: 0 0 1 ? * *)"), oneTime = List("send-mail"), custom = Nil)
    }
  }
}
