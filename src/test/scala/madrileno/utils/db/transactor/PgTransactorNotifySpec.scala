package madrileno.utils.db.transactor

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.data.Identifier

import scala.concurrent.duration.*

class PgTransactorNotifySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private def channel: Identifier =
    Identifier.fromValue(s"notify_test_${TestData.randomUuid().toString.replace("-", "_")}").toOption.get

  "PgTransactor.notify" should {
    "deliver a payload containing quotes and JSON verbatim" in {
      val name    = channel
      val payload = """{"name":"O'Brien","note":"it's \"quoted\""}"""
      transactor
        .listen(name, maxQueued = 4)
        .use { stream =>
          for {
            sub <- stream.take(1).compile.lastOrError.start
            _   <- IO.sleep(200.millis)
            _   <- transactor.notify(name, payload)
            got <- sub.joinWithNever
          } yield got.value shouldBe payload
        }
        .timeout(10.seconds)
    }
  }
}
