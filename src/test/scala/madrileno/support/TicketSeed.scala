package madrileno.support

import cats.effect.IO
import cats.syntax.all.*
import madrileno.ticket.domain.{EventId, EventSeat, EventSection, EventStatus, Money, OrderId, SeatId, SeatLabel, SectionId, SectionName}
import madrileno.user.domain.UserId
import madrileno.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

final case class SeededSeat(
  eventId: EventId,
  seatId: SeatId,
  orderId: OrderId,
  seatLabel: SeatLabel,
  price: Money)

// What to seed for one section of an event.
final case class SectionSpec(
  name: String,
  price: Money,
  seatLabels: List[SeatLabel])

final case class SeededEvent(
  eventId: EventId,
  startsAt: Instant,
  sections: List[EventSection]) {
  def allSeats: List[EventSeat]           = sections.flatMap(_.seats)
  def seat(label: String): EventSeat      = allSeats.find(_.label == SeatLabel(label)).getOrElse(sys.error(s"no seeded seat $label"))
  def section(name: String): EventSection = sections.find(_.name.unwrap == name).getOrElse(sys.error(s"no seeded section $name"))
}

// Events and their seating plan do have a write path (EventRepository.save / addSection), but specs that need a
// ticket's foreign keys, exact names, statuses or start times insert the rows with plain SQL instead: it is faster and
// does not depend on the code under test. Orders are normally written by the hold use case; here they are inserted too.
// The customer of an order must already exist.
object TicketSeed {

  private val insertEventWithStatus: Command[(UUID, String, OffsetDateTime, String)] =
    sql"""INSERT INTO event (id, name, starts_at, status, created_at, updated_at)
          VALUES ($uuid, $text, $timestamptz, $text, now(), now())""".command

  private val insertNamedSection: Command[(UUID, UUID, String, Long, String)] =
    sql"""INSERT INTO event_section (id, event_id, name, price_minor, currency)
          VALUES ($uuid, $uuid, $text, $int8, $text)""".command

  // Seeds an event with the given sections and seats. Returns the event as the read model describes it.
  def event(
    sections: List[SectionSpec],
    status: EventStatus = EventStatus.OnSale,
    startsAt: Instant = Instant.now().plusSeconds(86400)
  ): DB[SeededEvent] = {
    val session = summon[Session[IO]]
    val eventId = TestData.randomUuid()
    for {
      _      <- session.execute(insertEventWithStatus)((eventId, "Test event", startsAt.atOffset(ZoneOffset.UTC), status.toString))
      seeded <- sections.traverse { spec =>
                  val sectionId = TestData.randomUuid()
                  for {
                    _ <- session.execute(insertNamedSection)((sectionId, eventId, spec.name, spec.price.amountMinor, spec.price.currency.toString))
                    seats <- spec.seatLabels.traverse { label =>
                               val seatId = TestData.randomUuid()
                               session.execute(insertSeat)((seatId, sectionId, label.unwrap)).as(EventSeat(SeatId(seatId), label))
                             }
                  } yield EventSection(SectionId(sectionId), SectionName(spec.name), spec.price, seats)
                }
    } yield SeededEvent(EventId(eventId), startsAt, seeded)
  }

  private val insertEvent: Command[(UUID, String, OffsetDateTime)] =
    sql"""INSERT INTO event (id, name, starts_at, status, created_at, updated_at)
          VALUES ($uuid, $text, $timestamptz, 'OnSale', now(), now())""".command

  private val insertSection: Command[(UUID, UUID, Long, String)] =
    sql"""INSERT INTO event_section (id, event_id, name, price_minor, currency)
          VALUES ($uuid, $uuid, 'Parter', $int8, $text)""".command

  private val insertSeat: Command[(UUID, UUID, String)] =
    sql"INSERT INTO event_seat (id, section_id, label) VALUES ($uuid, $uuid, $text)".command

  private val insertOrder: Command[(UUID, UUID, UUID)] =
    sql"INSERT INTO ticket_order (id, event_id, customer_id, created_at) VALUES ($uuid, $uuid, $uuid, now())".command

  def seat(
    customerId: UserId,
    seatLabel: SeatLabel = SeatLabel("A-1"),
    price: Money = TestData.ticketPrice(),
    startsAt: Instant = Instant.now().plusSeconds(86400)
  ): DB[SeededSeat] = {
    val session   = summon[Session[IO]]
    val eventId   = TestData.randomUuid()
    val sectionId = TestData.randomUuid()
    val seatId    = TestData.randomUuid()
    val orderId   = TestData.randomUuid()
    for {
      _ <- session.execute(insertEvent)((eventId, "Test event", startsAt.atOffset(ZoneOffset.UTC)))
      _ <- session.execute(insertSection)((sectionId, eventId, price.amountMinor, price.currency.toString))
      _ <- session.execute(insertSeat)((seatId, sectionId, seatLabel.unwrap))
      _ <- session.execute(insertOrder)((orderId, eventId, customerId.unwrap))
    } yield SeededSeat(EventId(eventId), SeatId(seatId), OrderId(orderId), seatLabel, price)
  }
}
