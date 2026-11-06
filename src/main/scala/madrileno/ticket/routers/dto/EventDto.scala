package madrileno.ticket.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

// The seating plan is not part of the payload yet.
final case class EventDto(
  id: EventId,
  name: EventName,
  startsAt: Instant,
  status: EventStatus)
    derives Encoder.AsObject,
      Decoder

object EventDto {
  def apply(event: Event): EventDto = event.into[EventDto].transform
}
