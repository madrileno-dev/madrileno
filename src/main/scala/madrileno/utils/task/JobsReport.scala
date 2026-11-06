package madrileno.utils.task

import madrileno.utils.json.JsonProtocol.*

import java.time.Instant
import scala.concurrent.duration.{Duration, FiniteDuration}

enum JobHealth {
  case Healthy, Failing, Overdue, Stalled, Missing
}

enum JobsStatus {
  case Healthy, Unhealthy
}

final case class RecurringJob(
  name: String,
  schedule: String,
  health: JobHealth,
  nextRun: Option[Instant],
  lastSuccess: Option[Instant],
  lastFailure: Option[Instant],
  consecutiveFailures: Option[Int],
  pickedBy: Option[String])
    derives Encoder.AsObject,
      Decoder

final case class QueuedJob(
  task: String,
  health: JobHealth,
  nextRun: Instant,
  pickedBy: Option[String],
  lastHeartbeat: Option[Instant],
  lastFailure: Option[Instant],
  consecutiveFailures: Option[Int],
  payload: String)
    derives Encoder.AsObject,
      Decoder

final case class RegisteredJobs(
  recurring: List[String],
  oneTime: List[String],
  custom: List[String])
    derives Encoder.AsObject,
      Decoder

final case class JobsReport(
  status: JobsStatus,
  generatedAt: Instant,
  recurring: List[RecurringJob],
  running: List[QueuedJob],
  failing: List[QueuedJob],
  failingTotal: Int,
  overdue: List[QueuedJob],
  overdueTotal: Int,
  orphaned: List[QueuedJob],
  registered: RegisteredJobs)
    derives Encoder.AsObject,
      Decoder

object JobsReport {
  private val listLimit = 50

  private[task] def apply(
    recurringTasks: List[Task[?]],
    oneTimeTasks: List[OneTimeTask[?]],
    customTasks: List[CustomTask[?]],
    rows: List[TaskRow],
    config: SchedulerConfig,
    now: Instant
  ): JobsReport = {
    def olderThan(instant: Instant, limit: Duration): Boolean = limit match {
      case finite: FiniteDuration => instant.isBefore(now.minusMillis(finite.toMillis))
      case _                      => false
    }

    def health(row: TaskRow): JobHealth =
      if (row.picked && row.lastHeartbeat.forall(olderThan(_, config.heartbeatInterval * config.missedHeartbeatLimit.toDouble))) JobHealth.Stalled
      else if (!row.picked && olderThan(row.nextExecution, config.overdueAfter)) JobHealth.Overdue
      else if (row.consecutiveFailures.exists(_ > 0)) JobHealth.Failing
      else JobHealth.Healthy

    def queued(row: TaskRow): QueuedJob = {
      val raw = row.taskData.noSpaces
      QueuedJob(
        task = s"${row.taskName}/${row.taskInstance}",
        health = health(row),
        nextRun = row.nextExecution,
        pickedBy = row.pickedBy,
        lastHeartbeat = row.lastHeartbeat,
        lastFailure = row.lastFailure,
        consecutiveFailures = row.consecutiveFailures.filter(_ > 0),
        payload = if (raw.length > 120) raw.take(117) + "…" else raw
      )
    }

    val rowsByName = rows.groupBy(_.taskName)
    val knownNames =
      (recurringTasks.map(_.descriptor.taskName) ++ oneTimeTasks.map(_.descriptor.taskName) ++ customTasks.map(_.descriptor.taskName)).toSet

    val recurring = recurringTasks.map { task =>
      val row = rowsByName.get(task.descriptor.taskName).flatMap(_.headOption)
      RecurringJob(
        name = task.descriptor.taskName,
        schedule = renderSchedule(task.schedule),
        health = row.fold(JobHealth.Missing)(health),
        nextRun = row.map(_.nextExecution),
        lastSuccess = row.flatMap(_.lastSuccess),
        lastFailure = row.flatMap(_.lastFailure),
        consecutiveFailures = row.flatMap(_.consecutiveFailures).filter(_ > 0),
        pickedBy = row.flatMap(_.pickedBy)
      )
    }
    val running  = rows.filter(_.picked).map(queued)
    val failing  = rows.filter(_.consecutiveFailures.exists(_ > 0)).sortBy(_.lastFailure.map(_.toEpochMilli * -1L)).map(queued)
    val overdue  = rows.map(queued).filter(_.health == JobHealth.Overdue).sortBy(_.nextRun)
    val orphaned = rows.filterNot(row => knownNames.contains(row.taskName)).map(queued)

    val unhealthy = recurring.exists(_.health != JobHealth.Healthy) || running.exists(_.health == JobHealth.Stalled) || overdue.nonEmpty

    JobsReport(
      status = if (unhealthy) JobsStatus.Unhealthy else JobsStatus.Healthy,
      generatedAt = now,
      recurring = recurring,
      running = running,
      failing = failing.take(listLimit),
      failingTotal = failing.size,
      overdue = overdue.take(listLimit),
      overdueTotal = overdue.size,
      orphaned = orphaned,
      registered = RegisteredJobs(
        recurring = recurringTasks.map(task => s"${task.descriptor.taskName} (${renderSchedule(task.schedule)})").sorted,
        oneTime = oneTimeTasks.map(_.descriptor.taskName).sorted,
        custom = customTasks.map(_.descriptor.taskName).sorted
      )
    )
  }

  private def renderSchedule(schedule: Schedule): String = schedule match {
    case Schedule.Once                              => "once (immediate)"
    case Schedule.OnceAt(at)                        => s"once at $at"
    case Schedule.RecurringWithFixedRate(every, _)  => s"every $every (fixed rate)"
    case Schedule.RecurringWithFixedDelay(after, _) => s"every $after (fixed delay)"
    case Schedule.Cron(expression)                  => s"cron: ${expression.toString}"
    case Schedule.NextAt(at, _)                     => s"custom, next at $at"
  }
}
