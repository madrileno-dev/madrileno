package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

// Trimmed and non-blank, like EventName. Comparison is exact (case-sensitive), the same as the unique constraint
// event_section_event_id_name_key, so "VIP" and "vip" are two different sections.
opaque type SectionName = String
object SectionName extends Opaque[SectionName, String] {
  override def validate(value: String): Either[String, SectionName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Section name must not be empty")
  }
}
