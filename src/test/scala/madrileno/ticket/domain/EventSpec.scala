package madrileno.ticket.domain

import madrileno.user.domain.UserId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import java.util.UUID

class EventSpec extends AnyWordSpec with Matchers {

  private def uuid(n: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-$n%012d")

  private val now          = Instant.parse("2026-01-01T10:00:00Z")
  private val holdDuration = Duration.ofMinutes(15)
  private val customerId   = UserId(uuid(1))
  private val orderId      = OrderId(uuid(2))

  private def money(minor: Long, currency: Currency = Currency.PLN): Money = Money.from(minor, currency).getOrElse(fail("invalid price"))

  private val vipPrice    = money(30_000L, Currency.EUR)
  private val parterPrice = money(8_000L)
  private val vip1        = EventSeat(SeatId(uuid(11)), SeatLabel("V-1"))
  private val vip2        = EventSeat(SeatId(uuid(12)), SeatLabel("V-2"))
  private val parter1     = EventSeat(SeatId(uuid(13)), SeatLabel("P-1"))
  private val foreignSeat = SeatId(uuid(99))
  private val vipSection  = EventSection(SectionId(uuid(21)), SectionName("VIP"), vipPrice, List(vip1, vip2))
  private val parterSect  = EventSection(SectionId(uuid(22)), SectionName("Parter"), parterPrice, List(parter1))
  private val startsAt    = now.plus(Duration.ofDays(1))

  private val event = Event(EventId(uuid(3)), EventName("Concert"), startsAt, EventStatus.OnSale, now, now, List(vipSection, parterSect))

  // An event with more seats than one order may hold.
  private val bigSeats = (1 to Event.MaxSeatsPerOrder + 2).toList.map(n => EventSeat(SeatId(uuid(100 + n)), SeatLabel(s"B-$n")))
  private val bigEvent = event.copy(sections = List(EventSection(SectionId(uuid(23)), SectionName("Big"), parterPrice, bigSeats)))

  // Deterministic ticket ids derived from the seat id, so assertions can name them.
  private def ticketIdFor(seatId: SeatId): TicketId = TicketId(UUID.nameUUIDFromBytes(seatId.unwrap.toString.getBytes))

  private def hold(
    on: Event = event,
    seatIds: List[SeatId],
    at: Instant = now
  ): Either[HoldRejection, (Order, List[Ticket])] =
    on.holdSeats(orderId, ticketIdFor, customerId, seatIds, at, holdDuration)

  private def held(seatIds: List[SeatId]): (Order, List[Ticket]) =
    hold(seatIds = seatIds).fold(rejection => fail(s"hold rejected: $rejection"), identity)

  "Event.holdSeats" should {
    "create an order and one Held ticket per selected seat, in the order of selection" in {
      val (order, tickets) = held(List(parter1.id, vip1.id))
      order shouldBe Order(orderId, event.id, customerId, now)
      tickets.map(_.seatId) shouldBe List(parter1.id, vip1.id)
      tickets.map(_.id) shouldBe List(ticketIdFor(parter1.id), ticketIdFor(vip1.id))
      tickets.foreach { ticket =>
        ticket.status shouldBe TicketStatus.Held
        ticket.orderId shouldBe orderId
        ticket.chargeId shouldBe None
        ticket.paidAt shouldBe None
      }
    }

    "snapshot the label and the price of each seat's own section" in {
      val (_, tickets) = held(List(vip2.id, parter1.id))
      tickets.map(t => (t.seatLabel, t.price)) shouldBe List((vip2.label, vipPrice), (parter1.label, parterPrice))
    }

    "set the customer on the order and on every ticket" in {
      val (order, tickets) = held(List(vip1.id, vip2.id))
      order.customerId shouldBe customerId
      tickets.map(_.customerId).distinct shouldBe List(customerId)
    }

    "set heldUntil = now + hold duration and createdAt = updatedAt = now" in {
      val (order, tickets) = held(List(vip1.id))
      tickets.map(_.heldUntil) shouldBe List(now.plus(holdDuration))
      tickets.map(_.createdAt) shouldBe List(now)
      tickets.map(_.updatedAt) shouldBe List(now)
      order.createdAt shouldBe now
    }

    "reject an empty selection" in {
      hold(seatIds = Nil) shouldBe Left(HoldRejection.NoSeatsSelected)
    }

    "accept exactly the maximum number of seats per order" in {
      hold(on = bigEvent, seatIds = bigSeats.take(Event.MaxSeatsPerOrder).map(_.id)).map(_._2.size) shouldBe Right(8)
    }

    "reject more than the maximum number of seats per order" in {
      hold(on = bigEvent, seatIds = bigSeats.take(Event.MaxSeatsPerOrder + 1).map(_.id)) shouldBe Left(HoldRejection.TooManySeats)
    }

    "check the seat limit before duplicates and membership" in {
      hold(on = bigEvent, seatIds = List.fill(Event.MaxSeatsPerOrder + 1)(bigSeats.head.id)) shouldBe Left(HoldRejection.TooManySeats)
      hold(on = bigEvent, seatIds = List.fill(Event.MaxSeatsPerOrder + 1)(foreignSeat)) shouldBe Left(HoldRejection.TooManySeats)
    }

    "check the event state before the seat limit" in {
      hold(on = bigEvent.copy(status = EventStatus.Cancelled), seatIds = bigSeats.map(_.id)) shouldBe Left(HoldRejection.EventCancelled)
    }

    "reject a seat selected twice" in {
      hold(seatIds = List(vip1.id, parter1.id, vip1.id)) shouldBe Left(HoldRejection.DuplicateSeats)
    }

    "reject a seat that is not part of the event" in {
      hold(seatIds = List(vip1.id, foreignSeat)) shouldBe Left(HoldRejection.SeatNotInEvent)
    }

    "reject any hold on a cancelled event with EventCancelled" in {
      hold(on = event.copy(status = EventStatus.Cancelled), seatIds = List(vip1.id)) shouldBe Left(HoldRejection.EventCancelled)
    }

    "reject a hold once the event has started with EventNotOnSale, exactly at startsAt included" in {
      hold(seatIds = List(vip1.id), at = startsAt.minusNanos(1)).isRight shouldBe true
      hold(seatIds = List(vip1.id), at = startsAt) shouldBe Left(HoldRejection.EventNotOnSale)
      hold(seatIds = List(vip1.id), at = startsAt.plusSeconds(1)) shouldBe Left(HoldRejection.EventNotOnSale)
    }

    "check the event state before the selection: a cancelled event wins over an empty selection" in {
      hold(on = event.copy(status = EventStatus.Cancelled), seatIds = Nil) shouldBe Left(HoldRejection.EventCancelled)
    }

    "check the event state before the selection: a started event wins over duplicates and unknown seats" in {
      hold(seatIds = List(foreignSeat, foreignSeat), at = startsAt) shouldBe Left(HoldRejection.EventNotOnSale)
    }

    "report a cancelled event even when it has also started" in {
      hold(on = event.copy(status = EventStatus.Cancelled), seatIds = List(vip1.id), at = startsAt) shouldBe Left(HoldRejection.EventCancelled)
    }

    "check an empty selection before duplicates and membership" in {
      hold(seatIds = Nil) shouldBe Left(HoldRejection.NoSeatsSelected)
    }

    "check duplicates before membership" in {
      hold(seatIds = List(foreignSeat, foreignSeat)) shouldBe Left(HoldRejection.DuplicateSeats)
    }
  }

  "Event.addSection" should {
    val newSectionId = SectionId(uuid(40))
    val balcony      = SectionName("Balcony")
    val balconyPrice = money(5_000L)
    val labels       = List(SeatLabel("B-2"), SeatLabel("B-1"))
    // Deterministic seat ids derived from the label, so assertions can name them.
    def seatIdFor(label: SeatLabel): SeatId = SeatId(UUID.nameUUIDFromBytes(label.unwrap.getBytes))

    def add(
      on: Event = event,
      name: SectionName = balcony,
      price: Money = balconyPrice,
      seatLabels: List[SeatLabel] = labels,
      at: Instant = now
    ): Either[AddSectionRejection, (Event, EventSection)] =
      on.addSection(newSectionId, seatIdFor, name, price, seatLabels, at)

    "add the section with one seat per label, in the given order, and return the updated event" in {
      val expected = EventSection(
        newSectionId,
        balcony,
        balconyPrice,
        List(EventSeat(seatIdFor(SeatLabel("B-2")), SeatLabel("B-2")), EventSeat(seatIdFor(SeatLabel("B-1")), SeatLabel("B-1")))
      )
      add() shouldBe Right((event.copy(sections = event.sections :+ expected), expected))
    }

    "allow the first section of an event without sections" in {
      add(on = event.copy(sections = Nil)).map(_._1.sections.map(_.name)) shouldBe Right(List(balcony))
    }

    "reject EventCancelled for a cancelled event" in {
      add(on = event.copy(status = EventStatus.Cancelled)) shouldBe Left(AddSectionRejection.EventCancelled)
    }

    "reject EventNotOnSale once the event has started, exactly at startsAt (boundary)" in {
      add(at = startsAt) shouldBe Left(AddSectionRejection.EventNotOnSale)
      add(at = startsAt.plusSeconds(1)) shouldBe Left(AddSectionRejection.EventNotOnSale)
    }

    "accept one instant before startsAt" in {
      add(at = startsAt.minusNanos(1)).isRight shouldBe true
    }

    "reject NoSeats for an empty label list" in {
      add(seatLabels = Nil) shouldBe Left(AddSectionRejection.NoSeats)
    }

    "reject DuplicateSeatLabels when a label repeats" in {
      add(seatLabels = List(SeatLabel("B-1"), SeatLabel("B-1"))) shouldBe Left(AddSectionRejection.DuplicateSeatLabels)
    }

    "reject PriceMustBePositive for a zero price" in {
      add(price = money(0L)) shouldBe Left(AddSectionRejection.PriceMustBePositive)
    }

    "reject SectionNameTaken when a section with the name exists (exact comparison)" in {
      add(name = SectionName("VIP")) shouldBe Left(AddSectionRejection.SectionNameTaken)
      add(name = SectionName("vip")).isRight shouldBe true
    }

    "allow the same seat label in a different section" in {
      add(seatLabels = List(vip1.label)).isRight shouldBe true
    }

    "check the event state before everything else" in {
      val cancelled = event.copy(status = EventStatus.Cancelled)
      add(on = cancelled, name = SectionName("VIP"), price = money(0L), seatLabels = Nil) shouldBe Left(AddSectionRejection.EventCancelled)
      add(name = SectionName("VIP"), price = money(0L), seatLabels = Nil, at = startsAt) shouldBe Left(AddSectionRejection.EventNotOnSale)
    }

    "report a cancelled event even when it has also started" in {
      add(on = event.copy(status = EventStatus.Cancelled), at = startsAt) shouldBe Left(AddSectionRejection.EventCancelled)
    }

    "check NoSeats before duplicates, the price and the name" in {
      add(seatLabels = Nil, price = money(0L), name = SectionName("VIP")) shouldBe Left(AddSectionRejection.NoSeats)
    }

    "check duplicate labels before the price and the name" in {
      add(seatLabels = List(SeatLabel("B-1"), SeatLabel("B-1")), price = money(0L), name = SectionName("VIP")) shouldBe
        Left(AddSectionRejection.DuplicateSeatLabels)
    }

    "check the price before the name" in {
      add(price = money(0L), name = SectionName("VIP")) shouldBe Left(AddSectionRejection.PriceMustBePositive)
    }
  }

  "Event.create" should {
    val name = EventName("Concert")
    val id   = EventId(uuid(7))

    "create an OnSale event without sections, timestamped with now" in {
      Event.create(id, name, startsAt, now) shouldBe Right(Event(id, name, startsAt, EventStatus.OnSale, now, now, Nil))
    }

    "accept an event starting one millisecond after now" in {
      Event.create(id, name, now.plusMillis(1), now).isRight shouldBe true
    }

    "reject an event in the past" in {
      Event.create(id, name, now.minusSeconds(1), now) shouldBe Left(EventCreationRejection.EventInThePast)
    }

    "reject an event starting exactly now (boundary)" in {
      Event.create(id, name, now, now) shouldBe Left(EventCreationRejection.EventInThePast)
    }
  }
}
