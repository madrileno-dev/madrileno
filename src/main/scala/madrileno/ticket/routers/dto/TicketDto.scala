package madrileno.ticket.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.{Ticket, TicketId, TicketName}
import madrileno.utils.json.JsonProtocol.*

final case class TicketDto(id: TicketId, name: TicketName) derives Encoder.AsObject, Decoder

object TicketDto {
  def apply(ticket: Ticket): TicketDto = ticket.into[TicketDto].transform
}
