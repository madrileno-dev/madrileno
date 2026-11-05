package madrileno.ticket.routers.dto

import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

final case class OrderDto(
  id: OrderId,
  eventId: EventId,
  tickets: List[TicketDto])
    derives Encoder.AsObject,
      Decoder

object OrderDto {
  def apply(order: Order, tickets: List[Ticket]): OrderDto = OrderDto(order.id, order.eventId, tickets.map(TicketDto(_)))
}
