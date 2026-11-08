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
        flagged <- gaveUp.get
        _       <- unblock.complete(())
      } yield {
        took should be < 2.seconds
        flagged shouldBe true
      }
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
