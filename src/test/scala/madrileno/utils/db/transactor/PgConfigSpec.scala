package madrileno.utils.db.transactor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PgConfigSpec extends AnyWordSpec with Matchers {

  private val base = PgConfig(host = "db.internal", port = 6543, user = "app", database = "madrileno")

  "PgConfig.jdbcUrl" should {
    "disable SSL by default" in {
      base.jdbcUrl shouldBe "jdbc:postgresql://db.internal:6543/madrileno?sslmode=disable"
    }

    "require SSL without verification for Trusted" in {
      base.copy(ssl = PgConfigSSL.Trusted).jdbcUrl shouldBe "jdbc:postgresql://db.internal:6543/madrileno?sslmode=require"
    }

    "verify the certificate chain and host name for System" in {
      base.copy(ssl = PgConfigSSL.System).jdbcUrl shouldBe "jdbc:postgresql://db.internal:6543/madrileno?sslmode=verify-full"
    }
  }
}
