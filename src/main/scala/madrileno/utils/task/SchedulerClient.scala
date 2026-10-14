package madrileno.utils.task

import cats.effect.IO
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import org.typelevel.otel4s.trace.Tracer

class SchedulerClient private[task] (
  repository: ClientSchedulerRepository,
  transactor: Transactor,
  tracer: Tracer[IO]) {
  def schedule[A](task: Task[A]): IO[Boolean] =
    transactor.inSession(scheduleInSession(task))

  def scheduleInSession[A](task: Task[A]): DB[Boolean] =
    stamped(task).flatMap(repository.save)

  def scheduleTransactionally[A](task: Task[A]): DBInTransaction[Boolean] =
    stamped(task).flatMap(repository.save)

  private def stamped[A](task: Task[A]): IO[Task[A]] =
    tracer.currentSpanContext.map(context => task.copy(traceParent = context.filter(_.isValid).map(TraceParent.of)))

  private[task] def listTasks: IO[List[TaskRow]] =
    transactor.inSession(repository.list)
}
