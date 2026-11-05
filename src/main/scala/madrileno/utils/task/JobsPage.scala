package madrileno.utils.task

import scalatags.Text.TypedTag

import java.time.Instant

object JobsPage {

  def render(report: JobsReport): String = {
    import scalatags.Text.all.{head as stHead, *}
    import scalatags.Text.tags2.title

    html(
      stHead(meta(charset := "UTF-8"), title("Jobs")),
      body(style := "font-family:system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222")(
        h1(style := "margin:0 0 8px 0")("Jobs"),
        p(style := "margin:0 0 24px 0")(
          b(report.status.toString),
          span(style := "color:#666")(
            s" — generated at ${formatInstant(report.generatedAt)}. The same report is served as JSON to Accept: application/json."
          )
        ),
        recurringSection(report.recurring),
        queuedSection(s"Currently running (${report.running.size})", "Nothing in flight.", None, report.running),
        queuedSection(
          s"Failing (${report.failingTotal})",
          "No tasks are failing.",
          Some(s"Tasks with one or more consecutive failures. Showing the most recent first; ${report.failing.size} of ${report.failingTotal}."),
          report.failing
        ),
        queuedSection(
          s"Overdue (${report.overdueTotal})",
          "Nothing is waiting past its time.",
          Some(
            s"Rows still waiting longer than overdue-after past their time, so no scheduler is picking them up. Oldest first; ${report.overdue.size} of ${report.overdueTotal}."
          ),
          report.overdue
        ),
        if (report.orphaned.nonEmpty)
          queuedSection(
            s"Orphaned rows (${report.orphaned.size})",
            "",
            Some(
              "Rows whose task_name is not registered in the running app — usually tasks renamed or removed in code. The scheduler clears these lazily on pick."
            ),
            report.orphaned
          )
        else span(),
        registeredSection(report.registered)
      )
    ).render
  }

  private def recurringSection(jobs: List[RecurringJob]): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(s"Recurring tasks (${jobs.size})"),
      if (jobs.isEmpty) emptyHint("None registered.")
      else
        sectionTable(
          List("Name", "Schedule", "Health", "Next run", "Last success", "Last failure", "Failures", "Picked by"),
          jobs.map(job =>
            List(
              job.name,
              job.schedule,
              job.health.toString,
              job.nextRun.fold("—")(formatInstant),
              job.lastSuccess.fold("—")(formatInstant),
              job.lastFailure.fold("—")(formatInstant),
              job.consecutiveFailures.fold("—")(_.toString),
              job.pickedBy.getOrElse("—")
            )
          )
        )
    )
  }

  private def queuedSection(
    heading: String,
    whenEmpty: String,
    note: Option[String],
    jobs: List[QueuedJob]
  ): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(heading),
      note.fold(span())(text => p(style := "color:#666;margin:0 0 8px 0;font-size:13px")(text)),
      if (jobs.isEmpty) emptyHint(whenEmpty)
      else
        sectionTable(
          List("Task", "Health", "Next run", "Picked by", "Last heartbeat", "Last failure", "Failures", "Payload"),
          jobs.map(job =>
            List(
              job.task,
              job.health.toString,
              formatInstant(job.nextRun),
              job.pickedBy.getOrElse("—"),
              job.lastHeartbeat.fold("—")(formatInstant),
              job.lastFailure.fold("—")(formatInstant),
              job.consecutiveFailures.fold("—")(_.toString),
              job.payload
            )
          )
        )
    )
  }

  private def registeredSection(registered: RegisteredJobs): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2("Registered task types"),
      sectionTable(
        List("Kind", "Name"),
        registered.recurring.map(n => List("recurring", n)) ++
          registered.oneTime.map(n => List("one-time", n)) ++
          registered.custom.map(n => List("custom", n))
      )
    )
  }

  private def sectionTable(headers: List[String], tableRows: Seq[List[String]]): TypedTag[String] = {
    import scalatags.Text.all.*
    table(style := "border-collapse:collapse;width:100%;font-size:14px;margin-bottom:24px")(
      thead(tr(style := "background:#f5f5f5;text-align:left")(headers.map(h => th(style := "padding:6px 10px;border-bottom:1px solid #ddd")(h)))),
      tbody(
        tableRows.map(cells =>
          tr(style := "border-bottom:1px solid #eee")(
            cells.map(c =>
              td(style := "padding:6px 10px;vertical-align:top;font-family:ui-monospace,Menlo,monospace;font-size:13px;word-break:break-word")(c)
            )
          )
        )
      )
    )
  }

  private def emptyHint(text: String): TypedTag[String] = {
    import scalatags.Text.all.*
    p(style := "color:#666;font-style:italic;margin-bottom:24px")(text)
  }

  private def formatInstant(i: Instant): String = i.toString
}
