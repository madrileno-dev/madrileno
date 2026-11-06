package madrileno.ticket.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

// `sections` is the seating plan (with seat ids); it is empty for an event nobody has added a section to.
final case class EventDto(
  id: EventId,
  name: EventName,
  startsAt: Instant,
  status: EventStatus,
  sections: List[SectionDto])
    derives Encoder.AsObject,
      Decoder

object EventDto {
  def apply(event: Event): EventDto = event.into[EventDto].transform
}
