package madrileno.ticket.repositories

import cats.effect.IO
import madrileno.ticket.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*

import java.time.Instant

// The event tables are read-only for this module (no admin/CRUD yet), so the tables below map only the columns the read
// model needs; created_at/updated_at of `event` are left out on purpose. Do not use these tables for writes.
private[repositories] final case class EventRow(
  id: EventId,
  name: String,
  startsAt: Instant,
  status: EventStatus)

private[repositories] object EventRowTable extends Table[EventRow]("event") with IdTable[EventRow, EventId] {
  override val id: Column[EventId] = column("id", uuid.as[EventId])
  val name: Column[String]         = column("name", text)
  val startsAt: Column[Instant]    = column("starts_at", timestamptz.asInstant)
  val status: Column[EventStatus]  = column("status", text.asEnum[EventStatus])

  def mapping: (List[Column[?]], Codec[EventRow]) = (id, name, startsAt, status)
}

// Money is flattened into two columns (price_minor, currency), like in TicketRow.
private[repositories] final case class SectionRow(
  id: SectionId,
  eventId: EventId,
  name: String,
  priceMinor: Long,
  currency: Currency) {

  // Throws on a row that violates Money's invariant; the `price_minor > 0` CHECK constraint keeps such rows out.
  def price: Money =
    Money.from(priceMinor, currency).fold(error => throw new IllegalStateException(s"Corrupt price in section $id: $error"), identity)
}

private[repositories] object SectionRowTable extends Table[SectionRow]("event_section") with ForeignIdTable[EventId] {
  val id: Column[SectionId]               = column("id", uuid.as[SectionId])
  val eventId: Column[EventId]            = column("event_id", uuid.as[EventId])
  val name: Column[String]                = column("name", text)
  val priceMinor: Column[Long]            = column("price_minor", int8)
  val currency: Column[Currency]          = column("currency", text.asEnum[Currency])
  override val foreignId: Column[EventId] = eventId

  def mapping: (List[Column[?]], Codec[SectionRow]) = (id, eventId, name, priceMinor, currency)
}

private[repositories] final case class SeatRow(
  id: SeatId,
  sectionId: SectionId,
  label: SeatLabel)

private[repositories] object SeatRowTable extends Table[SeatRow]("event_seat") with ForeignIdTable[SectionId] {
  val id: Column[SeatId]                    = column("id", uuid.as[SeatId])
  val sectionId: Column[SectionId]          = column("section_id", uuid.as[SectionId])
  val label: Column[SeatLabel]              = column("label", text.as[SeatLabel])
  override val foreignId: Column[SectionId] = sectionId

  def mapping: (List[Column[?]], Codec[SeatRow]) = (id, sectionId, label)
}

class EventRepository {

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
          val sections       = sectionRows.sortBy(_.name).map { section =>
            val seats = seatsBySection.getOrElse(section.id, Nil).sortBy(_.label.unwrap).map(seat => EventSeat(seat.id, seat.label))
            EventSection(section.id, section.name, section.price, seats)
          }
          Some(Event(eventRow.id, eventRow.name, eventRow.startsAt, eventRow.status, sections))
        }
    }

  private val eventRepository: IdRepository[EventRow, EventId] =
    new IdRepository[EventRow, EventId](_.id) {
      override val table: EventRowTable.type = EventRowTable
    }

  private val sectionRepository: ForeignIdRepository[SectionRow, EventId] =
    new ForeignIdRepository[SectionRow, EventId] {
      override val table: SectionRowTable.type = SectionRowTable
    }

  private val seatRepository: ForeignIdRepository[SeatRow, SectionId] =
    new ForeignIdRepository[SeatRow, SectionId] {
      override val table: SeatRowTable.type = SeatRowTable
    }
}
