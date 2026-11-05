package madrileno.utils.db.transactor

import skunk.exception.{EofException, PostgresErrorException}

import java.net.ConnectException

final class DatabaseUnavailable(message: String) extends Exception(message)

object DatabaseUnavailable {
  private val unavailableStates = Set("57014", "57P01", "57P02", "57P03", "53300")

  def unapply(error: Throwable): Boolean = error match {
    case _: DatabaseUnavailable    => true
    case e: PostgresErrorException => unavailableStates.contains(e.code) || (e.code.startsWith("08") && e.code != "08P01")
    case _: ConnectException       => true
    case _: EofException           => true
    case _                         => false
  }
}
