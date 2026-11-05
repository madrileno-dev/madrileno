package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

opaque type SeatLabel = String
object SeatLabel extends Opaque[SeatLabel, String] {
  override def validate(value: String): Either[String, SeatLabel] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Seat label must not be empty")
  }
}
