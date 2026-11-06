package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

opaque type EventName = String
object EventName extends Opaque[EventName, String] {
  override def validate(value: String): Either[String, EventName] = TextRules.validate(value, "Event name")
}
