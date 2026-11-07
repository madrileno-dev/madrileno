package madrileno.utils.db.transactor

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader
import skunk.Session

import scala.concurrent.duration.*

enum PgConfigSSL {
  case None
  case Trusted
  case System
}

final case class PgConfig(
  host: String,
  port: Int = 5432,
  user: String,
  database: String,
  password: Option[String] = None,
  max: Int = 10,
  debug: Boolean = false,
  ssl: PgConfigSSL = PgConfigSSL.None,
  parameters: Map[String, String] = Session.DefaultConnectionParameters,
  commandCache: Int = 1024,
  queryCache: Int = 1024,
  parseCache: Int = 1024,
  readTimeout: Duration = Duration.Inf,
  acquireTimeout: Duration = 5.seconds,
  statementTimeout: Duration = 30.seconds,
  idleInTransactionTimeout: Duration = 1.minute,
  telemetry: PgTelemetryConfig = PgTelemetryConfig()) {
  def connectionParameters: Map[String, String] = {
    val timeouts = List("statement_timeout" -> statementTimeout, "idle_in_transaction_session_timeout" -> idleInTransactionTimeout).collect {
      case (name, timeout: FiniteDuration) => name -> timeout.toMillis.toString
    }
    timeouts.toMap ++ parameters
  }

  def jdbcUrl: String = {
    val sslMode = ssl match {
      case PgConfigSSL.None    => "disable"
      case PgConfigSSL.Trusted => "require"
      case PgConfigSSL.System  => "verify-full"
    }
    s"jdbc:postgresql://$host:$port/$database?sslmode=$sslMode"
  }
}

object PgConfig {
  given ConfigReader[PgConfig] = deriveReader[PgConfig]
}
