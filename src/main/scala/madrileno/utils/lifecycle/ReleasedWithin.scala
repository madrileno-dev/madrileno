package madrileno.utils.lifecycle

import cats.effect.{IO, Resource}

import scala.concurrent.duration.FiniteDuration

extension [A](resource: Resource[IO, A]) {
  def releasedWithin(limit: FiniteDuration, onGivingUp: IO[Unit]): Resource[IO, A] =
    Resource.applyFull { poll =>
      poll(resource.allocatedCase).map { case (a, release) =>
        (a, (exit: Resource.ExitCase) => release(exit).start.flatMap(_.joinWithUnit.timeoutTo(limit, onGivingUp)))
      }
    }
}
