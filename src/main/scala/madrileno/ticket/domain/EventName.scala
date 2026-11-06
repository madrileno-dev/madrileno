package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

opaque type EventName = String
object EventName extends Opaque[EventName, String] {
  override def validate(value: String): Either[String, EventName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Event name must not be empty")
  }
}
