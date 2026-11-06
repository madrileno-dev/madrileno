package madrileno.ticket.routers.dto

import madrileno.ticket.domain.EventName
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

// A blank name fails EventName's validation and a malformed date fails Instant's decoder, so both are rejected
// with a 400 before the handler runs. startsAt is ISO-8601; an offset (+01:00) is accepted and normalised to UTC.
final case class CreateEventRequest(name: EventName, startsAt: Instant) derives Decoder, Encoder.AsObject
