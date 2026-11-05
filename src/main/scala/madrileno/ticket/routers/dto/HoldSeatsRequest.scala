package madrileno.ticket.routers.dto

import madrileno.ticket.domain.SeatId
import madrileno.utils.json.JsonProtocol.*

final case class HoldSeatsRequest(seatIds: List[SeatId]) derives Decoder, Encoder.AsObject
