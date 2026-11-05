package madrileno.support

import cats.effect.IO
import madrileno.ticket.domain.{EventId, Money, OrderId, SeatId, SeatLabel}
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

// Event, section, seat and order have no repositories yet (they arrive with the hold use case), so ticket
// specs insert the rows a ticket's foreign keys need with plain SQL. The customer must already exist.
object TicketSeed {

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
