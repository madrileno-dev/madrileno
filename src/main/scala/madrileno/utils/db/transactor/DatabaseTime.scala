package madrileno.utils.db.transactor

import cats.effect.{IO, IOLocal, Ref}

import scala.concurrent.duration.*

final case class DatabaseUse(
  calls: Int,
  time: FiniteDuration,
  poolWait: FiniteDuration) {
  def add(elapsed: FiniteDuration): DatabaseUse = copy(calls = calls + 1, time = time + elapsed)

  def waited(elapsed: FiniteDuration): DatabaseUse = copy(poolWait = poolWait + elapsed)
}

object DatabaseUse {
  val none: DatabaseUse = DatabaseUse(0, Duration.Zero, Duration.Zero)
}

/** How much of a piece of work, typically one request, went to database calls. The tally is shared with the fibers the work starts. */
final class DatabaseTime private (current: Option[IOLocal[Option[Ref[IO, DatabaseUse]]]]) {

  def measure[A](call: IO[A]): IO[A] =
    timed(call)(_.add(_))

  def waitingForConnection[A](acquire: IO[A]): IO[A] =
    timed(acquire)(_.waited(_))

  private def timed[A](work: IO[A])(record: (DatabaseUse, FiniteDuration) => DatabaseUse): IO[A] =
    current.fold(work)(_.get.flatMap {
      case Some(tally) => IO.monotonic.flatMap(started => work.guarantee(IO.monotonic.flatMap(ended => tally.update(record(_, ended - started)))))
      case None        => work
    })

  def tracking[A](work: IO[A]): IO[(Either[Throwable, A], DatabaseUse)] =
    current match {
      case Some(local) =>
        Ref[IO].of(DatabaseUse.none).flatMap { tally =>
          local.getAndSet(Some(tally)).flatMap { previous =>
            work.attempt.guarantee(local.set(previous)).flatMap(outcome => tally.get.map(outcome -> _))
          }
        }
      case None => work.attempt.map(_ -> DatabaseUse.none)
    }
}

object DatabaseTime {
  val untracked: DatabaseTime = new DatabaseTime(None)

  def create: IO[DatabaseTime] = IOLocal(Option.empty[Ref[IO, DatabaseUse]]).map(local => new DatabaseTime(Some(local)))
}
