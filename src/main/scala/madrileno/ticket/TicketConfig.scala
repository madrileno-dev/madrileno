package madrileno.ticket

import pureconfig.*
import pureconfig.error.CannotConvert

import scala.concurrent.duration.FiniteDuration

/** How long a ticket stays held before the hold expires. */
final case class TicketConfig(holdDuration: FiniteDuration) {
  // `Ticket.hold` takes a java.time.Duration
  def holdDurationJava: java.time.Duration = java.time.Duration.ofNanos(holdDuration.toNanos)
}

object TicketConfig {
  // Validation lives in the reader, so a non-positive value fails config loading at startup rather than at first use.
  given ConfigReader[TicketConfig] =
    ConfigReader.derived[TicketConfig].emap { config =>
      Either.cond(
        config.holdDuration.toNanos > 0,
        config,
        CannotConvert(config.holdDuration.toString, "TicketConfig", "ticket.hold-duration must be greater than zero")
      )
    }
}
