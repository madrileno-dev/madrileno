package madrileno.ticket.domain

import cats.syntax.all.*
import madrileno.user.domain.UserId

import java.time.{Duration, Instant}

// Case names are persisted as text, do not rename them.
enum EventStatus {
  case OnSale, Cancelled
}

enum EventCreationRejection {
  case EventInThePast
}

enum HoldRejection {
  case NoSeatsSelected, TooManySeats, DuplicateSeats, EventNotOnSale, EventCancelled, SeatNotInEvent
}

final case class EventSeat(id: SeatId, label: SeatLabel)

final case class EventSection(
  id: SectionId,
  name: String,
  price: Money,
  seats: List[EventSeat])

// Read model of an event with its seating plan. Whether a seat is still free is deliberately NOT part of it:
// availability is decided by the database (partial unique index on live tickets), not by a check made here.
final case class Event(
  id: EventId,
  name: EventName,
  startsAt: Instant,
  status: EventStatus,
  createdAt: Instant,
  updatedAt: Instant,
  sections: List[EventSection]) {

  // Guard order: event state first (so a cancelled event is reported as gone no matter what was selected), then the
  // shape of the selection (empty, too many, duplicates), then membership of every seat. `ticketIdFor` supplies the id of each new ticket because
  // the domain does not generate ids; it must be defined for every id in `seatIds`.
  // Each ticket snapshots the label and the price of the seat's section at hold time.
  def holdSeats(
    orderId: OrderId,
    ticketIdFor: SeatId => TicketId,
    customerId: UserId,
    seatIds: List[SeatId],
    now: Instant,
    holdDuration: Duration
  ): Either[HoldRejection, (Order, List[Ticket])] =
    for {
      _      <- ensureOnSale(now)
      _      <- Either.cond(seatIds.nonEmpty, (), HoldRejection.NoSeatsSelected)
      _      <- Either.cond(seatIds.size <= Event.MaxSeatsPerOrder, (), HoldRejection.TooManySeats)
      _      <- Either.cond(seatIds.distinct.size == seatIds.size, (), HoldRejection.DuplicateSeats)
      picked <- seatIds.traverse(seatId => seatsById.get(seatId).toRight(HoldRejection.SeatNotInEvent))
    } yield {
      val tickets = picked.map { case (seat, section) =>
        Ticket.hold(ticketIdFor(seat.id), orderId, seat.id, customerId, seat.label, section.price, now, holdDuration)
      }
      (Order(orderId, id, customerId, now), tickets)
    }

  // A started event is no longer on sale. Boundary: sales stop exactly at startsAt.
  private def ensureOnSale(now: Instant): Either[HoldRejection, Unit] =
    status match {
      case EventStatus.Cancelled => Left(HoldRejection.EventCancelled)
      case EventStatus.OnSale    => Either.cond(now.isBefore(startsAt), (), HoldRejection.EventNotOnSale)
    }

  private def seatsById: Map[SeatId, (EventSeat, EventSection)] =
    sections.flatMap(section => section.seats.map(seat => seat.id -> (seat, section))).toMap
}

object Event {

  // Business rule: one order may hold at most this many seats, so a single customer cannot block a large part of
  // the hall with one request. Changing the limit is a product decision, not a technical one.
  val MaxSeatsPerOrder: Int = 8

  // A new event is OnSale and has no sections yet (the seating plan is added in a later step). `now` is passed in,
  // the domain never reads the clock. Boundary: an event starting exactly at `now` has already started, so it is rejected.
  def create(
    id: EventId,
    name: EventName,
    startsAt: Instant,
    now: Instant
  ): Either[EventCreationRejection, Event] =
    Either.cond(startsAt.isAfter(now), Event(id, name, startsAt, EventStatus.OnSale, now, now, Nil), EventCreationRejection.EventInThePast)
}
