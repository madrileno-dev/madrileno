package madrileno.support

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}

import java.util.concurrent.atomic.AtomicReference

final class SuiteResources {

  private val finalizers = new AtomicReference[List[IO[Unit]]](Nil)

  def allocate[A](resource: Resource[IO, A]): A = {
    val (value, release) = resource.allocated.unsafeRunSync()
    finalizers.updateAndGet(release :: _)
    value
  }

  def releaseAll(): Unit =
    finalizers.getAndSet(Nil).foldLeft(IO.unit)((released, release) => released.guarantee(release)).unsafeRunSync()
}
