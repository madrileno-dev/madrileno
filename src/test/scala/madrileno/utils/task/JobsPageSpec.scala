package madrileno.utils.task

import io.circe.Json
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class JobsPageSpec extends AnyWordSpec with Matchers {

  private val now = Instant.parse("2026-11-05T12:00:00Z")

  private def queued(
    task: String,
    health: JobHealth,
    failures: Option[Int] = None,
    payload: String = "{}"
  ) =
    QueuedJob(
      task = task,
      health = health,
      nextRun = now.minusSeconds(900),
      pickedBy = Option.when(health == JobHealth.Stalled)("worker-7"),
      lastHeartbeat = Option.when(health == JobHealth.Stalled)(now.minusSeconds(3600)),
      lastFailure = failures.map(n => now.minusSeconds(n.toLong * 60)),
      consecutiveFailures = failures,
      payload = payload
    )

  private val report = JobsReport(
    status = JobsStatus.Unhealthy,
    generatedAt = now,
    recurring = List(
      RecurringJob(
        "nightly-cleanup",
        "cron: 0 0 1 ? * *",
        JobHealth.Failing,
        Some(now.plusSeconds(3600)),
        Some(now.minusSeconds(86400)),
        Some(now.minusSeconds(120)),
        Some(53),
        None
      ),
      RecurringJob("hourly-digest", "every 1 hour (fixed rate)", JobHealth.Missing, None, None, None, None, None)
    ),
    running = List(queued("import-feed/feed-9", JobHealth.Stalled)),
    failing = List(queued("send-mail/m-17", JobHealth.Failing, failures = Some(41), payload = """{"to":"<ops@example.com>"}""")),
    failingTotal = 61,
    overdue = List(queued("send-mail/m-18", JobHealth.Overdue)),
    overdueTotal = 73,
    orphaned = List(queued("renamed-task/x", JobHealth.Healthy)),
    registered =
      RegisteredJobs(List("nightly-cleanup (cron: 0 0 1 ? * *)", "hourly-digest (every 1 hour (fixed rate))"), List("send-mail"), List("import-feed"))
  )

  private def leaves(json: Json): List[String] =
    json.fold(Nil, b => List(b.toString), n => List(n.toString), s => List(s), _.toList.flatMap(leaves), _.values.toList.flatMap(leaves))

  private def unescaped(html: String): String =
    html.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&")

  "JobsPage" should {
    "show every value the JSON report carries" in {
      val page    = unescaped(JobsPage.render(report))
      val missing = leaves(report.asJson).distinct.filterNot(page.contains)
      missing shouldBe empty
    }

    "escape payloads instead of emitting them as markup" in {
      val page = JobsPage.render(report)
      page should not include "<ops@example.com>"
      page should include("&lt;ops@example.com&gt;")
    }
  }
}
