package madrileno.utils.task

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import madrileno.utils.db.transactor.DBInTransaction

import java.time.Instant
import scala.concurrent.duration.*

final case class TaskDescriptor[A](taskName: String)(using val encoder: Encoder[A], val decoder: Decoder[A])

sealed trait Schedule
sealed trait Once      extends Schedule
sealed trait Recurring extends Schedule
sealed trait Custom    extends Schedule
object Schedule {
  case object Once                                                                              extends Once
  final case class OnceAt(at: Instant)                                                          extends Once
  final case class RecurringWithFixedRate(every: Duration, initialDelay: Duration = 0.seconds)  extends Recurring
  final case class RecurringWithFixedDelay(after: Duration, initialDelay: Duration = 0.seconds) extends Recurring
  final case class Cron(expression: CronExpression)                                             extends Recurring
  final case class NextAt[A](at: Instant, payload: A)                                           extends Custom
}

final case class Task[A] private[task] (
  taskInstance: String,
  descriptor: TaskDescriptor[A],
  payload: A,
  execution: Task[A] => IO[Unit | A | Schedule.NextAt[A]],
  version: Long,
  priority: Short,
  schedule: Schedule,
  consecutiveFailures: Option[Int] = None,
  scheduledAt: Option[Instant] = None,
  maxRetries: Option[Int] = None,
  onAbandon: Option[Task[A] => DBInTransaction[Unit]] = None,
  traceParent: Option[String] = None) {
  def taskId: String = s"${descriptor.taskName}/$taskInstance"
}

final case class OneTimeTask[A](
  descriptor: TaskDescriptor[A],
  execution: Task[A] => IO[Unit],
  maxRetries: Option[Int] = None,
  onAbandon: Option[Task[A] => DBInTransaction[Unit]] = None) {
  def instance(
    taskInstance: String,
    payload: A,
    at: Option[Instant] = None,
    priority: Short = Task.DefaultPriority
  ): Task[A] = {
    Task(
      taskInstance = taskInstance,
      descriptor = descriptor,
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = at.fold[Schedule](Schedule.Once)(Schedule.OnceAt(_)),
      maxRetries = maxRetries,
      onAbandon = onAbandon
    )
  }
}

final case class CustomTask[A](
  descriptor: TaskDescriptor[A],
  execution: Task[A] => IO[Unit | Schedule.NextAt[A]],
  maxRetries: Option[Int] = None,
  onAbandon: Option[Task[A] => DBInTransaction[Unit]] = None) {
  def instance(
    taskInstance: String,
    payload: A,
    firstAt: Instant,
    priority: Short = Task.DefaultPriority
  ): Task[A] = {
    Task(
      taskInstance = taskInstance,
      descriptor = descriptor,
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = Schedule.NextAt(firstAt, payload),
      maxRetries = maxRetries,
      onAbandon = onAbandon
    )
  }
}

object Task {
  def recurring[A](
    name: String,
    schedule: Recurring,
    payload: A = (),
    priority: Short = DefaultPriority
  )(
    execution: Task[A] => IO[Unit | A]
  )(using
    Encoder[A],
    Decoder[A]
  ): Task[A] = {
    Task(
      taskInstance = "recurring",
      descriptor = TaskDescriptor[A](name),
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = schedule
    )
  }

  def oneTime[A](descriptor: TaskDescriptor[A])(execution: Task[A] => IO[Unit]): OneTimeTask[A] = {
    OneTimeTask(descriptor, execution)
  }

  def custom[A](descriptor: TaskDescriptor[A])(execution: A => IO[Unit | Schedule.NextAt[A]]): CustomTask[A] = {
    CustomTask(descriptor, task => execution(task.payload))
  }

  val DefaultPriority: Short = 0
}
