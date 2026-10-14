package madrileno.utils.task

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import io.circe.Json
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*
import skunk.{Codec, Session}

import java.time.Instant

private[task] final case class TaskRow(
  taskName: String,
  taskInstance: String,
  taskData: Json,
  nextExecution: Instant,
  picked: Boolean,
  pickedBy: Option[String],
  lastSuccess: Option[Instant],
  lastFailure: Option[Instant],
  consecutiveFailures: Option[Int],
  lastHeartbeat: Option[Instant],
  version: Long,
  priority: Short)

object TaskRow {
  def fromTask[A](task: Task[A], now: Instant): TaskRow = {
    val nextExecution = task.schedule match {
      case Schedule.Once                                     => now
      case Schedule.OnceAt(at)                               => at
      case Schedule.RecurringWithFixedRate(_, initialDelay)  => now.plusMillis(initialDelay.toMillis)
      case Schedule.RecurringWithFixedDelay(_, initialDelay) => now.plusMillis(initialDelay.toMillis)
      case Schedule.Cron(expression)                         =>
        expression
          .nextFrom(now)
          .getOrElse(throw new IllegalStateException(s"Failed to calculate next execution time for cron expression: ${expression} at $now"))
      case Schedule.NextAt(at, _) => at
    }

    TaskRow(
      taskName = task.descriptor.taskName,
      taskInstance = task.taskInstance,
      taskData = task.descriptor.encoder(task.payload),
      nextExecution = nextExecution,
      picked = false,
      pickedBy = None,
      lastSuccess = None,
      lastFailure = None,
      consecutiveFailures = None,
      lastHeartbeat = None,
      version = task.version,
      priority = task.priority
    )
  }
}

object TaskRowTable extends Table[TaskRow]("scheduled_task") {
  val taskName: Column[String]                 = column("task_name", text)
  val taskInstance: Column[String]             = column("task_instance", text)
  val taskData: Column[Json]                   = column("task_data", jsonb)
  val nextExecution: Column[Instant]           = column("next_execution", timestamptz.asInstant)
  val picked: Column[Boolean]                  = column("picked", bool)
  val pickedBy: Column[Option[String]]         = column("picked_by", text.opt)
  val lastSuccess: Column[Option[Instant]]     = column("last_success", timestamptz.asInstant.opt)
  val lastFailure: Column[Option[Instant]]     = column("last_failure", timestamptz.asInstant.opt)
  val consecutiveFailures: Column[Option[Int]] = column("consecutive_failures", int4.opt)
  val lastHeartbeat: Column[Option[Instant]]   = column("last_heartbeat", timestamptz.asInstant.opt)
  val version: Column[Long]                    = column("version", int8)
  val priority: Column[Short]                  = column("priority", int2)

  def mapping: (List[Column[?]], Codec[TaskRow]) =
    (
      taskName,
      taskInstance,
      taskData,
      nextExecution,
      picked,
      pickedBy,
      lastSuccess,
      lastFailure,
      consecutiveFailures,
      lastHeartbeat,
      version,
      priority
    )
}

private[task] class ClientSchedulerRepository(using clock: Clock[IO]) {
  def save[A](task: Task[A]): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val row = TaskRow.fromTask(task, now)
      session
        .option(sql"""INSERT INTO ${table.n} VALUES (${table.c})
           ON CONFLICT (${table.taskName.n}, ${table.taskInstance.n}) DO UPDATE SET
           ${table.taskData.n} = ${table.taskData.n("EXCLUDED")},
           ${table.nextExecution.n} = ${table.nextExecution.n("EXCLUDED")},
           ${table.priority.n} = ${table.priority.n("EXCLUDED")}
           WHERE ${table.n}.${table.picked.n} = false
           RETURNING ${table.*}""".query(table.c))(row)
        .map(_.isDefined)
    }
  }

  def list: DB[List[TaskRow]] = {
    val q = sql"""SELECT ${table.*} FROM ${table.n}""".query(table.c)
    summon[Session[IO]].execute(q)
  }

  protected val table = TaskRowTable
}

private[task] class SchedulerRepository(
  schedulerName: String,
  startTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]]
)(using clock: Clock[IO])
    extends ClientSchedulerRepository {
  def registerOnStartup[A](task: Task[A]): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val row = TaskRow.fromTask(task, now)
      session
        .option(sql"""INSERT INTO ${table.n} VALUES (${table.c})
           ON CONFLICT (${table.taskName.n}, ${table.taskInstance.n}) DO UPDATE SET
           ${table.priority.n} = ${table.priority.n("EXCLUDED")}
           WHERE ${table.n}.${table.picked.n} = false
           RETURNING ${table.*}""".query(table.c))(row)
        .map(_.isDefined)
    }
  }

  def updateHeartbeat(task: Task[?]): DB[Unit] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.lastHeartbeat.n} = ${table.lastHeartbeat.c}
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), task.descriptor.taskName, task.taskInstance, task.version))
        .void
    }
  }

  def reschedule[A](
    task: Task[A],
    nextExecution: Instant,
    newPayload: Option[A] = None
  ): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val taskData = newPayload.fold(task.descriptor.encoder(task.payload))(task.descriptor.encoder(_))
      val command  =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.lastSuccess.n} = ${table.lastSuccess.c},
          ${table.consecutiveFailures.n} = ${table.consecutiveFailures.c},
          ${table.taskData.n} = ${table.taskData.c},
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), Some(0), taskData, nextExecution, task.descriptor.taskName, task.taskInstance, task.version))
        .map(_ != Completion.Update(0))
    }
  }

  def markFailure[A](
    task: Task[A],
    nextExecution: Instant,
    consecutiveFailures: Int
  ): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.lastFailure.n} = ${table.lastFailure.c},
          ${table.consecutiveFailures.n} = ${table.consecutiveFailures.c},
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), Some(consecutiveFailures), nextExecution, task.descriptor.taskName, task.taskInstance, task.version))
        .map(_ != Completion.Update(0))
    }
  }

  def remove(task: Task[?]): DB[Boolean] = {
    val session = summon[Session[IO]]

    val command =
      sql"""DELETE FROM ${table.n}
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
    session
      .execute(command)((task.descriptor.taskName, task.taskInstance, task.version))
      .map(_ != Completion.Delete(0))
  }

  private def reconstructTask[A](
    row: TaskRow,
    descriptor: TaskDescriptor[A],
    execution: Task[A] => IO[Unit | A | Schedule.NextAt[A]],
    schedule: A => Schedule,
    scheduledAt: Option[Instant],
    maxRetries: Option[Int],
    onAbandon: Option[Task[A] => DBInTransaction[Unit]]
  ): Option[Task[?]] = {
    descriptor.decoder.decodeJson(row.taskData) match {
      case Right(value) =>
        Some(
          Task(
            taskInstance = row.taskInstance,
            descriptor = descriptor,
            payload = value,
            execution = execution,
            version = row.version,
            priority = row.priority,
            schedule = schedule(value),
            consecutiveFailures = row.consecutiveFailures,
            scheduledAt = scheduledAt,
            maxRetries = maxRetries,
            onAbandon = onAbandon
          )
        )
      case Left(_) =>
        None
    }
  }

  private def deletePoisonRow(row: TaskRow): DB[Unit] = {
    val session = summon[Session[IO]]
    val command =
      sql"""DELETE FROM ${table.n}
      WHERE ${table.taskName.n} = ${table.taskName.c}
        AND ${table.taskInstance.n} = ${table.taskInstance.c}
        AND ${table.version.n} = ${table.version.c}
     """.command
    session
      .execute(command)((row.taskName, row.taskInstance, row.version))
      .void
  }

  def pickAndMark(limit: Int): DB[(List[Task[?]], List[String])] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val query =
        sql"""WITH locked_executions as (
          UPDATE ${table.n} st1 SET
            ${table.picked.n} = true,
            ${table.pickedBy.n} = ${table.pickedBy.c},
            ${table.lastHeartbeat.n} = ${table.lastHeartbeat.c},
            ${table.version.n} = ${table.version.n} + 1
          WHERE (st1.${table.taskName.n}, st1.${table.taskInstance.n}) IN (
            SELECT st2.${table.taskName.n}, st2.${table.taskInstance.n}
            FROM ${table.n} st2
            WHERE st2.${table.picked.n} = false AND st2.${table.nextExecution.n} <= ${table.nextExecution.c}
            ORDER BY st2.${table.priority.n} DESC, st2.${table.nextExecution.n} ASC
            FOR UPDATE SKIP LOCKED
            LIMIT $int4
          ) RETURNING st1.*
    )
    SELECT * FROM locked_executions ORDER BY ${table.priority.n} DESC, ${table.nextExecution.n} ASC
       """.query(table.c).withQuerySummary("scheduler.pick")
      session.execute(query)((Some(schedulerName), Some(now), now, limit)).flatMap { rows =>
        val (failures, successes) = rows.partitionMap { row =>
          val found = oneTimeTasks
            .find(_.descriptor.taskName == row.taskName)
            .flatMap { t =>
              reconstructTask(row, t.descriptor, t.execution, _ => Schedule.Once, Some(row.nextExecution), t.maxRetries, t.onAbandon)
            }
            .orElse {
              customTasks
                .find(_.descriptor.taskName == row.taskName)
                .flatMap { t =>
                  reconstructTask(
                    row,
                    t.descriptor,
                    t.execution,
                    v => Schedule.NextAt(row.nextExecution, v),
                    Some(row.nextExecution),
                    t.maxRetries,
                    t.onAbandon
                  )
                }
            }
            .orElse {
              startTasks
                .find(_.descriptor.taskName == row.taskName)
                .flatMap { t =>
                  reconstructTask(row, t.descriptor, t.execution, _ => t.schedule, Some(row.nextExecution), t.maxRetries, t.onAbandon)
                }
            }

          found.toRight(row)
        }

        failures.traverse(deletePoisonRow).as((successes, failures.map(r => s"${r.taskName}/${r.taskInstance}")))
      }
    }
  }

  def reviveStaleExecutions(staleThreshold: Instant): DB[Int] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.picked.n} = true
          AND (${table.lastHeartbeat.n} IS NULL OR ${table.lastHeartbeat.n} <= ${table.lastHeartbeat.c})
       """.command
      session
        .execute(command)((now, Some(staleThreshold)))
        .map { case Completion.Update(n) => n; case _ => 0 }
    }
  }

}
