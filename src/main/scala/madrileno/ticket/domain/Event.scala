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

enum AddSectionRejection {
  case EventCancelled, EventNotOnSale, NoSeats, DuplicateSeatLabels, PriceMustBePositive, SectionNameTaken
}

final case class EventSeat(id: SeatId, label: SeatLabel)

final case class EventSection(
  id: SectionId,
  name: SectionName,
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

  // Adds a section with one seat per label. Guard order: event state first (same rule as holdSeats, so a seating plan
  // cannot change once sales are over), then the shape of the input (no seats, duplicate labels), then the price,
  // then the name against the existing sections. Ids are passed in because the domain does not generate them;
  // `seatIdFor` must be defined for every label in `seatLabels`.
  // The name check is only race-free because the caller serialises additions per event (the event row is locked);
  // the unique constraint event_section_event_id_name_key is the backstop and must never fire.
  def addSection(
    sectionId: SectionId,
    seatIdFor: SeatLabel => SeatId,
    name: SectionName,
    price: Money,
    seatLabels: List[SeatLabel],
    now: Instant
  ): Either[AddSectionRejection, (Event, EventSection)] =
    for {
      _ <- ensureOnSale(now, AddSectionRejection.EventCancelled, AddSectionRejection.EventNotOnSale)
      _ <- Either.cond(seatLabels.nonEmpty, (), AddSectionRejection.NoSeats)
      _ <- Either.cond(seatLabels.distinct.size == seatLabels.size, (), AddSectionRejection.DuplicateSeatLabels)
      // Money itself accepts 0 but event_section.price_minor requires > 0 (CHECK): free tickets are unsupported, and
      // this is where the rule is enforced for the domain.
      _ <- Either.cond(price.amountMinor > 0, (), AddSectionRejection.PriceMustBePositive)
      _ <- Either.cond(!sections.exists(_.name == name), (), AddSectionRejection.SectionNameTaken)
    } yield {
      val section = EventSection(sectionId, name, price, seatLabels.map(label => EventSeat(seatIdFor(label), label)))
      (copy(sections = sections :+ section), section)
    }

  private def ensureOnSale(now: Instant): Either[HoldRejection, Unit] =
    ensureOnSale(now, HoldRejection.EventCancelled, HoldRejection.EventNotOnSale)

  // A started event is no longer on sale. Boundary: sales stop exactly at startsAt.
  private def ensureOnSale[R](
    now: Instant,
    whenCancelled: R,
    whenNotOnSale: R
  ): Either[R, Unit] =
    status match {
      case EventStatus.Cancelled => Left(whenCancelled)
      case EventStatus.OnSale    => Either.cond(now.isBefore(startsAt), (), whenNotOnSale)
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
