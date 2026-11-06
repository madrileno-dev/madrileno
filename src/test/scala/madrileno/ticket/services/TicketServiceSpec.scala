package madrileno.ticket.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{SectionSpec, SeededEvent, TestData, TestGivens, TestTransactor, TicketSeed}
import madrileno.ticket.TicketConfig
import madrileno.ticket.domain.*
import madrileno.ticket.repositories.{EventRepository, OrderRepository, TicketRepository}
import madrileno.user.domain.User
import madrileno.user.repositories.UserRepository
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

// These tests commit for real (the service opens its own transactions), so every test seeds its own event, seats
// and customers and never shares a seat with another test.
class TicketServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Clock[IO]        = IO.asyncForIO
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  given UUIDGen[IO]      = UUIDGen.fromSecureRandom[IO](using IO.asyncForIO, TestGivens.secureRandom)

  private lazy val userRepo   = new UserRepository
  private lazy val ticketRepo = new TicketRepository
  private lazy val orderRepo  = new OrderRepository
  private val holdDuration    = 10.minutes
  private lazy val service    = new TicketService(ticketRepo, new EventRepository, orderRepo, TicketConfig(holdDuration), transactor)

  private val price = TestData.ticketPrice(12_000L)

  private def seedCustomer(): IO[User] = {
    val customer = TestData.user()
    withSession(userRepo.create(customer, Instant.now())).as(customer)
  }

  private def seedEvent(
    labels: List[String] = List("A-1"),
    status: EventStatus = EventStatus.OnSale,
    startsAt: Instant = Instant.now().plusSeconds(86400)
  ): IO[SeededEvent] =
    withSession(TicketSeed.event(List(SectionSpec("Parter", price, labels.map(SeatLabel(_)))), status, startsAt))

  private val countLive: Query[UUID, Long] =
    sql"SELECT count(*) FROM ticket WHERE seat_id = $uuid AND status IN ('Held', 'Paid')".query(int8)

  private val countOrdersOfCustomer: Query[UUID, Long] =
    sql"SELECT count(*) FROM ticket_order WHERE customer_id = $uuid".query(int8)

  private def liveTickets(seat: EventSeat): IO[Long] =
    withSession(summon[Session[IO]].unique(countLive)(seat.id.unwrap))

  private def ordersOf(customer: User): IO[Long] =
    withSession(summon[Session[IO]].unique(countOrdersOfCustomer)(customer.id.unwrap))

  private def command(
    event: SeededEvent,
    customer: User,
    seats: EventSeat*
  ): HoldSeatsCommand =
    HoldSeatsCommand(event.eventId, customer.id, seats.map(_.id).toList)

  "TicketService.holdSeats" should {
    "hold the seats: persist the order and one Held ticket per seat" in {
      for {
        customer <- seedCustomer()
        event    <- seedEvent(List("A-1", "A-2"))
        before   <- IO.realTimeInstant
        held     <- holdOrFail(command(event, customer, event.seat("A-1"), event.seat("A-2")))
        stored   <- withSession(held.tickets.traverse(t => ticketRepo.find(t.id)))
      } yield {
        val (order, tickets) = (held.order, held.tickets)
        order.eventId shouldBe event.eventId
        order.customerId shouldBe customer.id
        tickets.map(_.seatLabel) shouldBe List(SeatLabel("A-1"), SeatLabel("A-2"))
        tickets.map(_.price).distinct shouldBe List(price)
        tickets.map(_.status).distinct shouldBe List(TicketStatus.Held)
        tickets.map(_.orderId).distinct shouldBe List(order.id)
        // heldUntil is derived from the configured duration.
        tickets.foreach(t => t.heldUntil.isAfter(before.plusSeconds(holdDuration.toSeconds - 5)) shouldBe true)
        stored.flatten shouldBe tickets
      }
    }

    "report an unknown event" in {
      seedCustomer().flatMap { customer =>
        service
          .holdSeats(HoldSeatsCommand(TestData.randomEventId(), customer.id, List(TestData.randomSeatId())))
          .asserting(_ shouldBe HoldSeatsResult.EventNotFound)
      }
    }

    "report a cancelled event and an event that has started, writing nothing" in {
      for {
        customer  <- seedCustomer()
        cancelled <- seedEvent(status = EventStatus.Cancelled)
        started   <- seedEvent(startsAt = Instant.now().minusSeconds(60))
        r1        <- service.holdSeats(command(cancelled, customer, cancelled.seat("A-1")))
        r2        <- service.holdSeats(command(started, customer, started.seat("A-1")))
        live1     <- liveTickets(cancelled.seat("A-1"))
        live2     <- liveTickets(started.seat("A-1"))
        orders    <- ordersOf(customer)
      } yield {
        r1 shouldBe HoldSeatsResult.EventCancelled
        r2 shouldBe HoldSeatsResult.EventNotOnSale
        (live1, live2, orders) shouldBe (0L, 0L, 0L)
      }
    }

    "report invalid selections" in {
      for {
        customer <- seedCustomer()
        event    <- seedEvent()
        other    <- seedEvent()
        seat = event.seat("A-1")
        empty      <- service.holdSeats(command(event, customer))
        duplicates <- service.holdSeats(command(event, customer, seat, seat))
        foreign    <- service.holdSeats(command(event, customer, other.seat("A-1")))
      } yield {
        empty shouldBe HoldSeatsResult.NoSeatsSelected
        duplicates shouldBe HoldSeatsResult.DuplicateSeats
        foreign shouldBe HoldSeatsResult.SeatNotInEvent
      }
    }

    "report SeatAlreadyTaken when the seat is held, and leave the first hold intact" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent()
        seat = event.seat("A-1")
        r1     <- service.holdSeats(command(event, first, seat))
        r2     <- service.holdSeats(command(event, second, seat))
        live   <- liveTickets(seat)
        orders <- ordersOf(second)
      } yield {
        r1 shouldBe a[HoldSeatsResult.SeatsHeld]
        r2 shouldBe HoldSeatsResult.SeatAlreadyTaken
        (live, orders) shouldBe (1L, 0L)
      }
    }

    "roll the whole order back when one of its seats is taken" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent(List("A-1", "A-2"))
        _      <- service.holdSeats(command(event, first, event.seat("A-2")))
        r      <- service.holdSeats(command(event, second, event.seat("A-1"), event.seat("A-2")))
        liveA1 <- liveTickets(event.seat("A-1"))
        orders <- ordersOf(second)
      } yield {
        r shouldBe HoldSeatsResult.SeatAlreadyTaken
        // A-1 was inserted before the conflicting A-2: neither it nor the order may survive.
        (liveA1, orders) shouldBe (0L, 0L)
      }
    }

    "let exactly one of two parallel holds of the same seat win" in {
      for {
        a     <- seedCustomer()
        b     <- seedCustomer()
        event <- seedEvent()
        seat = event.seat("A-1")
        (ra, rb) <- (service.holdSeats(command(event, a, seat)), service.holdSeats(command(event, b, seat))).parTupled
        live     <- liveTickets(seat)
      } yield {
        List(ra, rb).count(isHeld) shouldBe 1
        List(ra, rb).count(_ == HoldSeatsResult.SeatAlreadyTaken) shouldBe 1
        live shouldBe 1L
      }
    }

    "let exactly one of many parallel holds of the same seat win" in {
      for {
        customers <- List.fill(6)(seedCustomer()).sequence
        event     <- seedEvent()
        seat = event.seat("A-1")
        results <- customers.parTraverse(c => service.holdSeats(command(event, c, seat)))
        live    <- liveTickets(seat)
      } yield {
        results.count(isHeld) shouldBe 1
        results.count(_ == HoldSeatsResult.SeatAlreadyTaken) shouldBe 5
        live shouldBe 1L
      }
    }

    // Inserting in request order would make [A,B] and [B,A] wait for each other (deadlock, 40P01). Repeated because the
    // interleaving is timing dependent; every round uses fresh seats.
    "never deadlock on holds of the same seats in opposite order" in {
      def round: IO[List[HoldSeatsResult]] =
        for {
          a     <- seedCustomer()
          b     <- seedCustomer()
          event <- seedEvent(List("A-1", "A-2"))
          (s1, s2) = (event.seat("A-1"), event.seat("A-2"))
          results <- (service.holdSeats(command(event, a, s1, s2)), service.holdSeats(command(event, b, s2, s1))).parMapN(List(_, _))
        } yield results

      List.fill(20)(round).sequence.asserting { rounds =>
        rounds.foreach { results =>
          results.count(isHeld) shouldBe 1
          results.count(_ == HoldSeatsResult.SeatAlreadyTaken) shouldBe 1
        }
        rounds.size shouldBe 20
      }
    }

    "allow a hold on a seat whose previous ticket was cancelled" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent()
        seat = event.seat("A-1")
        held  <- holdOrFail(command(event, first, seat))
        _     <- cancel(held.tickets.head.id)
        again <- service.holdSeats(command(event, second, seat))
        live  <- liveTickets(seat)
      } yield {
        again shouldBe a[HoldSeatsResult.SeatsHeld]
        live shouldBe 1L
      }
    }
  }

  private def isHeld(result: HoldSeatsResult): Boolean =
    result match {
      case _: HoldSeatsResult.SeatsHeld => true
      case _                            => false
    }

  private def holdOrFail(cmd: HoldSeatsCommand): IO[HoldSeatsResult.SeatsHeld] =
    service.holdSeats(cmd).flatMap {
      case held: HoldSeatsResult.SeatsHeld => IO.pure(held)
      case other                           => IO.raiseError(new AssertionError(s"expected SeatsHeld, got $other"))
    }

  private def cancel(id: TicketId): IO[Unit] =
    transactor.inTransaction(ticketRepo.update(id, _.cancel(Instant.now()))).void

  "TicketService.payTicket" should {
    "report ChargeAlreadyUsed when the charge already paid another ticket, leaving the second ticket Held" in {
      for {
        customer <- seedCustomer()
        event    <- seedEvent(List("A-1", "A-2"))
        first    <- holdOrFail(command(event, customer, event.seat("A-1")))
        second   <- holdOrFail(command(event, customer, event.seat("A-2")))
        charge = ChargeId("ch_reused_service")
        r1     <- service.payTicket(PayTicketCommand(first.tickets.head.id, customer.id, charge))
        r2     <- service.payTicket(PayTicketCommand(second.tickets.head.id, customer.id, charge))
        stored <- withSession(ticketRepo.find(second.tickets.head.id))
      } yield {
        r1 shouldBe a[PayTicketResult.TicketPaid]
        r2 shouldBe PayTicketResult.ChargeAlreadyUsed
        stored.map(_.status) shouldBe Some(TicketStatus.Held)
      }
    }
  }

  "TicketService.createEvent" should {
    "persist an OnSale event that getEvent returns" in {
      val startsAt = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
      for {
        result <- service.createEvent(CreateEventCommand(EventName("Concert"), startsAt))
        created = result match {
                    case CreateEventResult.EventCreated(event) => event
                    case other                                 => fail(s"unexpected $other")
                  }
        found <- service.getEvent(created.id)
      } yield {
        created.status shouldBe EventStatus.OnSale
        found.map(e => (e.name, e.startsAt, e.status)) shouldBe Some((EventName("Concert"), startsAt, EventStatus.OnSale))
      }
    }

    "reject an event in the past and store nothing" in {
      service.createEvent(CreateEventCommand(EventName("Old"), Instant.now().minusSeconds(60))).map(_ shouldBe CreateEventResult.EventInThePast)
    }
  }
}
