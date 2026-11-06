package madrileno.utils.db.transactor

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource
import skunk.Session

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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

  "PgConfig.connectionParameters" should {
    "send the statement and idle-in-transaction timeouts in milliseconds next to skunk's defaults" in {
      base.copy(statementTimeout = 15.seconds, idleInTransactionTimeout = 2.minutes).connectionParameters shouldBe
        Session.DefaultConnectionParameters ++ Map("statement_timeout" -> "15000", "idle_in_transaction_session_timeout" -> "120000")
    }

    "leave a timeout set to Inf to the server's own setting" in {
      val parameters = base.copy(statementTimeout = Duration.Inf, idleInTransactionTimeout = Duration.Inf).connectionParameters
      parameters shouldBe Session.DefaultConnectionParameters
    }

    "let a parameter given explicitly win over the timeout fields" in {
      base
        .copy(parameters = Session.DefaultConnectionParameters + ("statement_timeout" -> "0"))
        .connectionParameters("statement_timeout") shouldBe "0"
    }
  }

  "PgConfig" should {
    "bound a request's database waits by default" in {
      base.acquireTimeout shouldBe 5.seconds
      base.statementTimeout shouldBe 30.seconds
      base.idleInTransactionTimeout shouldBe 1.minute
      base.operationTimeout shouldBe 1.minute
    }

    "read the pool size and the timeouts from the pg block" in {
      val config = ConfigSource
        .string(
          """host = "h", user = "u", database = "d", max = 3, acquire-timeout = 250ms, statement-timeout = 2s, idle-in-transaction-timeout = Inf, operation-timeout = 10m"""
        )
        .loadOrThrow[PgConfig]
      config.max shouldBe 3
      config.acquireTimeout shouldBe 250.millis
      config.statementTimeout shouldBe 2.seconds
      config.idleInTransactionTimeout shouldBe Duration.Inf
      config.operationTimeout shouldBe 10.minutes
    }
  }

  "The pg block of application.conf" should {
    def load(env: Map[String, String]): PgConfig =
      ConfigSource
        .fromConfig(
          ConfigFactory
            .parseMap(env.asJava)
            .withFallback(ConfigFactory.parseResources("application.conf"))
            .resolve(ConfigResolveOptions.noSystem())
            .getConfig("pg")
        )
        .loadOrThrow[PgConfig]

    "ship the bounded defaults" in {
      val config = load(Map.empty)
      config.max shouldBe 10
      config.acquireTimeout shouldBe 5.seconds
      config.statementTimeout shouldBe 30.seconds
      config.idleInTransactionTimeout shouldBe 1.minute
      config.operationTimeout shouldBe 1.minute
    }

    "take the pool size and the timeouts from the environment" in {
      val config =
        load(
          Map(
            "PG_POOL_SIZE"                   -> "25",
            "PG_ACQUIRE_TIMEOUT"             -> "2s",
            "PG_STATEMENT_TIMEOUT"           -> "2m",
            "PG_IDLE_IN_TRANSACTION_TIMEOUT" -> "Inf",
            "PG_OPERATION_TIMEOUT"           -> "5m"
          )
        )
      config.max shouldBe 25
      config.acquireTimeout shouldBe 2.seconds
      config.statementTimeout shouldBe 2.minutes
      config.idleInTransactionTimeout shouldBe Duration.Inf
      config.operationTimeout shouldBe 5.minutes
    }
  }
}
