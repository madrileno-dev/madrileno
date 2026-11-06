package madrileno.utils.db.transactor

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class DatabaseTimeSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  "DatabaseTime" should {
    "add up the calls and the time measured while tracking, also in fibers the work starts" in {
      for {
        time   <- DatabaseTime.create
        result <- time.tracking(time.measure(IO.sleep(50.millis)) *> time.measure(IO.sleep(50.millis)).start.flatMap(_.joinWithNever))
      } yield {
        result._2.calls shouldBe 2
        result._2.time should be >= 100.millis
      }
    }

    "count a call that failed, and carry nothing over to the next tracking" in {
      for {
        time   <- DatabaseTime.create
        failed <- time.tracking(time.measure(IO.raiseError[Unit](new RuntimeException("connection reset"))))
        _      <- time.measure(IO.sleep(10.millis))
        next   <- time.tracking(IO.unit)
      } yield {
        failed._1.isLeft shouldBe true
        failed._2.calls shouldBe 1
        next._2 shouldBe DatabaseUse.none
      }
    }

    "measure nothing when untracked" in {
      DatabaseTime.untracked.tracking(DatabaseTime.untracked.measure(IO.pure(1))).map(_ shouldBe (Right(1), DatabaseUse.none))
    }
  }
}
