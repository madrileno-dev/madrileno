package madrileno.ticket.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

final case class MoneyDto(amountMinor: Long, currency: Currency) derives Encoder.AsObject, Decoder

// chargeId is deliberately not exposed: it is a payment-provider reference, not customer-facing data.
final case class TicketDto(
  id: TicketId,
  orderId: OrderId,
  seatLabel: SeatLabel,
  price: MoneyDto,
  status: TicketStatus,
  heldUntil: Instant)
    derives Encoder.AsObject,
      Decoder

object TicketDto {
  def apply(ticket: Ticket): TicketDto = ticket.into[TicketDto].transform
}
