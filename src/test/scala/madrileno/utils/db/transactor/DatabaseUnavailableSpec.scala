package madrileno.utils.db.transactor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import skunk.exception.{EofException, PostgresErrorException}

import java.net.ConnectException

class DatabaseUnavailableSpec extends AnyWordSpec with Matchers {

  private def postgresError(code: String): PostgresErrorException =
    new PostgresErrorException(sql = "SELECT 1", sqlOrigin = None, info = Map('S' -> "ERROR", 'C' -> code, 'M' -> "failed"), history = Nil)

  "DatabaseUnavailable" should {
    "cover a request that waited too long for a pooled connection" in {
      DatabaseUnavailable.unapply(new DatabaseUnavailable("no connection")) shouldBe true
    }

    "cover the server states that mean it cannot answer now" in {
      List("57014", "57P01", "57P02", "57P03", "53300", "08000", "08006").map(code =>
        code -> DatabaseUnavailable.unapply(postgresError(code))
      ) shouldBe
        List("57014", "57P01", "57P02", "57P03", "53300", "08000", "08006").map(_ -> true)
    }

    "cover a refused or dropped connection" in {
      DatabaseUnavailable.unapply(new ConnectException("Connection refused")) shouldBe true
      DatabaseUnavailable.unapply(EofException(5, 0)) shouldBe true
    }

    "leave errors in the request itself to the internal-error path" in {
      List("23505", "42P01", "40P01", "25P03").map(code => code -> DatabaseUnavailable.unapply(postgresError(code))) shouldBe
        List("23505", "42P01", "40P01", "25P03").map(_ -> false)
      DatabaseUnavailable.unapply(new RuntimeException("boom")) shouldBe false
    }
  }
}
