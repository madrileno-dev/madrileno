package madrileno.ticket.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.{Duration, Instant}
import java.util.UUID

opaque type TicketId = UUID
object TicketId extends Opaque[TicketId, UUID]

enum PayRejection {
  case NotOwner, AlreadyPaid, AlreadyCancelled, HoldExpired
}

enum CancelRejection {
  case AlreadyPaid, AlreadyCancelled
}

// Invariant: `status == Paid` exactly when `chargeId` and `paidAt` are both defined;
// Held and Cancelled tickets have both set to None. Only `pay` produces a Paid ticket
// and it sets all three together, so the invariant cannot be broken through the public API's transitions.
final case class Ticket(
  id: TicketId,
  orderId: OrderId,
  seatId: SeatId,
  customerId: UserId,
  seatLabel: SeatLabel,
  price: Money,
  status: TicketStatus,
  heldUntil: Instant,
  chargeId: Option[ChargeId],
  paidAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant) {

  // Guard order matters: authorization comes first so that a foreign user learns nothing about the
  // ticket's state (NotOwner wins even for paid/cancelled/expired tickets). Then state, then time.
  def pay(
    requesterId: UserId,
    chargeId: ChargeId,
    now: Instant
  ): Either[PayRejection, Ticket] = {
    if (requesterId != customerId) Left(PayRejection.NotOwner)
    else {
      status match {
        case TicketStatus.Paid      => Left(PayRejection.AlreadyPaid)
        case TicketStatus.Cancelled => Left(PayRejection.AlreadyCancelled)
        case TicketStatus.Held      =>
          // Boundary: the hold is valid strictly before `heldUntil`; now == heldUntil is already expired.
          if (!now.isBefore(heldUntil)) Left(PayRejection.HoldExpired)
          else Right(copy(status = TicketStatus.Paid, chargeId = Some(chargeId), paidAt = Some(now), updatedAt = now))
      }
    }
  }

  // No requester parameter: the expiry task (system) cancels tickets too, and ownership is checked elsewhere
  // for customer-initiated cancellation. A Held ticket is cancelable even past `heldUntil`: expired holds
  // are exactly what the expiry task cancels. Paid tickets are not cancelable (refunds are out of scope).
  def cancel(now: Instant): Either[CancelRejection, Ticket] = {
    status match {
      case TicketStatus.Held      => Right(copy(status = TicketStatus.Cancelled, updatedAt = now))
      case TicketStatus.Paid      => Left(CancelRejection.AlreadyPaid)
      case TicketStatus.Cancelled => Left(CancelRejection.AlreadyCancelled)
    }
  }
}

object Ticket {
  // `heldUntil` is computed once here and stored, so later changes to the hold duration never affect existing tickets.
  def hold(
    id: TicketId,
    orderId: OrderId,
    seatId: SeatId,
    customerId: UserId,
    seatLabel: SeatLabel,
    price: Money,
    now: Instant,
    holdDuration: Duration
  ): Ticket =
    Ticket(
      id = id,
      orderId = orderId,
      seatId = seatId,
      customerId = customerId,
      seatLabel = seatLabel,
      price = price,
      status = TicketStatus.Held,
      heldUntil = now.plus(holdDuration),
      chargeId = None,
      paidAt = None,
      createdAt = now,
      updatedAt = now
    )
}
