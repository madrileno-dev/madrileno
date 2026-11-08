package madrileno.utils.lifecycle

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO, Ref, Resource}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class ReleasedWithinSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  "releasedWithin" should {
    "stop waiting for a release that outlives the limit and say so" in {
      for {
        gaveUp  <- Ref[IO].of(false)
        unblock <- Deferred[IO, Unit]
        took    <- Resource
                  .make(IO.unit)(_ => unblock.get)
                  .releasedWithin(200.millis, gaveUp.set(true))
                  .use_
                  .start
                  .flatMap(_.join.timeout(5.seconds))
                  .timed
                  .map(_._1)
        flagged <- gaveUp.get.guarantee(unblock.complete(()).void)
      } yield {
        took should be < 2.seconds
        flagged shouldBe true
      }
    }

    "leave the abandoned release running" in {
      for {
        unblock  <- Deferred[IO, Unit]
        finished <- Deferred[IO, Unit]
        _        <- Resource.make(IO.unit)(_ => unblock.get *> finished.complete(()).void).releasedWithin(100.millis, IO.unit).use_
        _        <- unblock.complete(())
        done     <- finished.get.timeout(5.seconds).attempt
      } yield done shouldBe Right(())
    }

    "stop promptly when cancelled while acquiring" in {
      Resource
        .eval(IO.sleep(10.seconds))
        .releasedWithin(5.seconds, IO.unit)
        .use_
        .start
        .flatMap(fiber => IO.sleep(100.millis) *> fiber.cancel)
        .timed
        .map { case (took, _) => took should be < 2.seconds }
    }

    "tell the resources inside that they were cancelled" in {
      for {
        exit    <- Deferred[IO, Resource.ExitCase]
        started <- Deferred[IO, Unit]
        fiber   <- Resource
                   .makeCase(IO.unit)((_, ec) => exit.complete(ec).void)
                   .releasedWithin(5.seconds, IO.unit)
                   .use(_ => started.complete(()) *> IO.never)
                   .start
        _    <- started.get *> fiber.cancel
        seen <- exit.get.timeout(5.seconds)
      } yield seen shouldBe Resource.ExitCase.Canceled
    }

    "wait for a release that finishes within the limit" in {
      for {
        gaveUp <- Ref[IO].of(false)
        closed <- Ref[IO].of(false)
        _      <- Resource.make(IO.unit)(_ => IO.sleep(50.millis) *> closed.set(true)).releasedWithin(5.seconds, gaveUp.set(true)).use_
        done   <- closed.get
        warned <- gaveUp.get
      } yield {
        done shouldBe true
        warned shouldBe false
      }
    }

    "pass on an error the release raises within the limit" in {
      Resource
        .make(IO.unit)(_ => IO.raiseError(new IllegalStateException("leak")))
        .releasedWithin(5.seconds, IO.unit)
        .use_
        .attempt
        .map(_.left.toOption.map(_.getMessage) shouldBe Some("leak"))
    }
  }
}
