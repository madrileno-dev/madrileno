package madrileno.utils.db.transactor

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO}
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

    "report how long a call waited for a pooled connection while another call held it" in {
      for {
        time    <- DatabaseTime.create
        holding <- Deferred[IO, Unit]
        result  <-
          PgTransactor.resource(pgConfig.copy(max = 1), time).use { tx =>
            tx.inSession(holding.complete(()) *> summon[Session[IO]].unique(sql"SELECT 1 FROM pg_sleep(0.4)".query(int4))).start.flatMap { holder =>
              holding.get *> time.tracking(tx.inSession(summon[Session[IO]].unique(sql"SELECT 1".query(int4)))) <* holder.joinWithNever
            }
          }
      } yield {
        result._2.calls shouldBe 1
        result._2.poolWait should be >= 300.millis
        result._2.time should be >= result._2.poolWait
      }
    }

    "report no wait for a call that got a free connection and then failed" in {
      for {
        time   <- DatabaseTime.create
        result <-
          trackedTransactor(time).use { tx =>
            time.tracking(tx.inSession {
              summon[Session[IO]].unique(sql"SELECT 1 FROM pg_sleep(0.2)".query(int4)) *> summon[Session[IO]].unique(sql"SELECT 1 / 0".query(int4))
            })
          }
      } yield {
        result._1.isLeft shouldBe true
        result._2.time should be >= 200.millis
        result._2.poolWait should be < 100.millis
      }
    }
  }
}
