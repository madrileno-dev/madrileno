package madrileno.utils.db.transactor

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{RecordingTracer, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.int4
import skunk.implicits.*

class PgTransactorTracingSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  "PgTransactor tracing" should {
    "record the statement but not the pool recycling that follows each session" in {
      for {
        recorder <- RecordingTracer.create
        spans    <- tracedTransactor(recorder.tracerProvider).use { tx =>
                   tx.inSession(summon[Session[IO]].unique(sql"SELECT 1".query(int4))).replicateA_(2) *> recorder.spans
                 }
      } yield spans.map(_.getName) shouldBe List("SELECT", "SELECT")
    }
  }
}
