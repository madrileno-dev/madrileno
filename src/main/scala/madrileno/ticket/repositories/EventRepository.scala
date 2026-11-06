package madrileno.ticket.repositories

import cats.effect.IO
import madrileno.ticket.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.time.Instant

// Events are only ever inserted (`save`), there is no update path for the event row. Sections and seats are written
// only through `addSection`.
private[repositories] final case class EventRow(
  id: EventId,
  name: EventName,
  startsAt: Instant,
  status: EventStatus,
  createdAt: Instant,
  updatedAt: Instant)

private[repositories] object EventRow {
  // Sections and seats live in their own tables, so only the event itself is flattened into the row.
  def apply(event: Event): EventRow =
    EventRow(event.id, event.name, event.startsAt, event.status, event.createdAt, event.updatedAt)
}

private[repositories] object EventRowTable extends Table[EventRow]("event") with IdTable[EventRow, EventId] {
  override val id: Column[EventId] = column("id", uuid.as[EventId])
  val name: Column[EventName]      = column("name", text.as[EventName])
  val startsAt: Column[Instant]    = column("starts_at", timestamptz.asInstant)
  val status: Column[EventStatus]  = column("status", text.asEnum[EventStatus])
  val createdAt: Column[Instant]   = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]   = column("updated_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[EventRow]) = (id, name, startsAt, status, createdAt, updatedAt)
}

// Money is flattened into two columns (price_minor, currency), like in TicketRow.
private[repositories] final case class SectionRow(
  id: SectionId,
  eventId: EventId,
  name: SectionName,
  priceMinor: Long,
  currency: Currency) {

  // Throws on a row that violates Money's invariant; the `price_minor > 0` CHECK constraint keeps such rows out.
  def price: Money =
    Money.from(priceMinor, currency).fold(error => throw new IllegalStateException(s"Corrupt price in section $id: $error"), identity)
}

private[repositories] object SectionRowTable
    extends Table[SectionRow]("event_section")
    with IdTable[SectionRow, SectionId]
    with ForeignIdTable[EventId] {
  override val id: Column[SectionId]      = column("id", uuid.as[SectionId])
  val eventId: Column[EventId]            = column("event_id", uuid.as[EventId])
  val name: Column[SectionName]           = column("name", text.as[SectionName])
  val priceMinor: Column[Long]            = column("price_minor", int8)
  val currency: Column[Currency]          = column("currency", text.asEnum[Currency])
  override val foreignId: Column[EventId] = eventId

  def mapping: (List[Column[?]], Codec[SectionRow]) = (id, eventId, name, priceMinor, currency)
}

private[repositories] object SectionRow {
  def apply(section: EventSection, eventId: EventId): SectionRow =
    SectionRow(section.id, eventId, section.name, section.price.amountMinor, section.price.currency)
}

private[repositories] final case class SeatRow(
  id: SeatId,
  sectionId: SectionId,
  label: SeatLabel)

private[repositories] object SeatRowTable extends Table[SeatRow]("event_seat") with IdTable[SeatRow, SeatId] with ForeignIdTable[SectionId] {
  override val id: Column[SeatId]           = column("id", uuid.as[SeatId])
  val sectionId: Column[SectionId]          = column("section_id", uuid.as[SectionId])
  val label: Column[SeatLabel]              = column("label", text.as[SeatLabel])
  override val foreignId: Column[SectionId] = sectionId

  def mapping: (List[Column[?]], Codec[SeatRow]) = (id, sectionId, label)
}

class EventRepository {

  // Inserts a NEW event. Its sections and seats are not written here, see `addSection`.
  def save(event: Event): DB[Unit] =
    eventRepository.create(EventRow(event)).void

  // Reads only the event row: `sections` is always empty. Use findWithSeats when the seating plan is needed.
  def find(id: EventId): DB[Option[Event]] =
    eventRepository.findById(id).map(_.map(toEvent(_, Nil)))

  // The only write path of the seating plan, same shape as TicketRepository.update. The event row is locked first,
  // which serialises concurrent additions to the same event: the section-name check in `f` (the domain) then
  // sees every section committed before ours and is race-free. The plan is loaded only after the lock is held.
  // The lock is FOR NO KEY UPDATE, not FOR UPDATE, on purpose: the event row itself is never updated here, and
  // FOR UPDATE would conflict with the FOR KEY SHARE lock that Postgres takes on the event row for the foreign key
  // check of ticket_order.event_id, so adding a section would block every concurrent hold on that event.
  // FOR NO KEY UPDATE still conflicts with itself (additions stay serialised) but not with FOR KEY SHARE.
  // On Left nothing is written; None when the event does not exist.
  // A unique violation on event_section_event_id_name_key / event_seat_section_id_label_key can therefore only be a bug
  // (the domain rejects duplicates, the lock removes the race): those constraints are a backstop and are deliberately
  // not translated into a result.
  def addSection[E](id: EventId, f: Event => Either[E, (Event, EventSection)]): DBInTransaction[Option[Either[E, (Event, EventSection)]]] =
    eventRepository.findById(id, Lock.ForNoKeyUpdate).flatMap {
      case None    => IO.pure(None)
      case Some(_) =>
        findWithSeats(id).flatMap {
          case None        => IO.pure(None)
          case Some(event) =>
            f(event) match {
              case Left(rejection)           => IO.pure(Some(Left(rejection)))
              case Right((updated, section)) =>
                for {
                  _ <- sectionRepository.create(SectionRow(section, id))
                  _ <- seatRepository.createAll(section.seats.map(seat => SeatRow(seat.id, section.id, seat.label)))
                } yield Some(Right((updated, section)))
            }
        }
    }

  // Three plain queries (event, its sections, their seats) assembled in Scala, instead of one wide join.
  // Sections and seats are sorted by name/label so the result does not depend on the physical row order.
  def findWithSeats(id: EventId): DB[Option[Event]] =
    eventRepository.findById(id).flatMap {
      case None           => IO.pure(None)
      case Some(eventRow) =>
        for {
          sectionRows <- sectionRepository.findByForeignId(id)
          seatRows    <- seatRepository.findByForeignIds(sectionRows.map(_.id))
        } yield {
          val seatsBySection = seatRows.groupBy(_.sectionId)
          val sections       = sectionRows.sortBy(_.name.unwrap).map { section =>
            val seats = seatsBySection.getOrElse(section.id, Nil).sortBy(_.label.unwrap).map(seat => EventSeat(seat.id, seat.label))
            EventSection(section.id, section.name, section.price, seats)
          }
          Some(toEvent(eventRow, sections))
        }
    }

  private def toEvent(row: EventRow, sections: List[EventSection]): Event =
    Event(row.id, row.name, row.startsAt, row.status, row.createdAt, row.updatedAt, sections)

  private val eventRepository: IdRepository[EventRow, EventId] =
    new IdRepository[EventRow, EventId](_.id) {
      override val table: EventRowTable.type = EventRowTable
    }

  private val sectionRepository: IdRepository[SectionRow, SectionId] & ForeignIdRepository[SectionRow, EventId] =
    new IdRepository[SectionRow, SectionId](_.id) with ForeignIdRepository[SectionRow, EventId] {
      override val table: SectionRowTable.type = SectionRowTable
    }

  private val seatRepository: IdRepository[SeatRow, SeatId] & ForeignIdRepository[SeatRow, SectionId] =
    new IdRepository[SeatRow, SeatId](_.id) with ForeignIdRepository[SeatRow, SectionId] {
      override val table: SeatRowTable.type = SeatRowTable
    }
}
