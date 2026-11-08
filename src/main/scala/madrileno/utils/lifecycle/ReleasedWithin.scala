package madrileno.utils.lifecycle

import cats.effect.{IO, Resource}

import scala.concurrent.duration.FiniteDuration

extension [A](resource: Resource[IO, A]) {
  def releasedWithin(limit: FiniteDuration, onGivingUp: IO[Unit]): Resource[IO, A] =
    Resource.make(resource.allocated) { case (_, release) => release.start.flatMap(_.joinWithUnit.timeoutTo(limit, onGivingUp)) }.map(_._1)
}
