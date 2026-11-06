package madrileno.utils.db.transactor

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.int4
import skunk.implicits.*

import scala.concurrent.duration.*

class PgTransactorTimingSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  "PgTransactor" should {
    "report each session and transaction to DatabaseTime" in {
      for {
        time   <- DatabaseTime.create
        result <- trackedTransactor(time).use { tx =>
                    time.tracking(
                      tx.inSession(summon[Session[IO]].unique(sql"SELECT 1 FROM pg_sleep(0.1)".query(int4))) *>
                        tx.inTransaction(summon[Session[IO]].unique(sql"SELECT 1".query(int4)))
                    )
                  }
      } yield {
        result._2.calls shouldBe 2
        result._2.time should be >= 100.millis
      }
    }
  }
}
