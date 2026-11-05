package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

opaque type ChargeId = String
object ChargeId extends Opaque[ChargeId, String] {
  override def validate(value: String): Either[String, ChargeId] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Charge id must not be empty")
  }
}
