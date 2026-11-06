package madrileno.utils.db.transactor

import cats.effect.{IO, IOLocal, Ref}

import scala.concurrent.duration.*

final case class DatabaseUse(calls: Int, time: FiniteDuration) {
  def add(elapsed: FiniteDuration): DatabaseUse = DatabaseUse(calls + 1, time + elapsed)
}

object DatabaseUse {
  val none: DatabaseUse = DatabaseUse(0, Duration.Zero)
}

/** How much of a piece of work, typically one request, went to database calls. The tally is shared with the fibers the work starts. */
final class DatabaseTime private (current: Option[IOLocal[Option[Ref[IO, DatabaseUse]]]]) {

  def measure[A](call: IO[A]): IO[A] =
    current.fold(call)(_.get.flatMap {
      case Some(tally) => IO.monotonic.flatMap(started => call.guarantee(IO.monotonic.flatMap(ended => tally.update(_.add(ended - started)))))
      case None        => call
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
