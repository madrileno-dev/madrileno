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
import scala.concurrent.duration.*

// Every suite gets its own Postgres container, but the tests share it and cancelExpiredHolds is global: it also
// cancels the expired holds other tests left behind. Assertions are therefore made on the specific tickets each test
// created, and the global count is compared with a before-the-run count of expired Held rows, never with a literal,
// so the result does not depend on the order of the tests.
// Time is controlled by the clock each service is built with: `pastService` places its holds in the past (their
// heldUntil lies about 1h50m before now), `service` runs on the real clock and is the one whose task is under test.
class TicketServiceExpiredHoldsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val uuidGen: UUIDGen[IO] = UUIDGen.fromSecureRandom[IO](using IO.asyncForIO, TestGivens.secureRandom)
  private val telemetry            = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private val realClock: Clock[IO] = IO.asyncForIO
  private val pastClock: Clock[IO] = TestGivens.fixedClock(Instant.now().minusSeconds(2 * 3600))
  private val holdDuration         = 10.minutes
  private lazy val userRepo        = new UserRepository
  private lazy val ticketRepo      = new TicketRepository

  private def newService(clock: Clock[IO]): TicketService =
    new TicketService(ticketRepo, new EventRepository, new OrderRepository, TicketConfig(holdDuration), transactor)(using clock, uuidGen, telemetry)

  private lazy val service     = newService(realClock)
  private lazy val pastService = newService(pastClock)

  private val price = TestData.ticketPrice(12_000L)

  private def seedCustomer(): IO[User] = {
    val customer = TestData.user()
    withSession(userRepo.create(customer, Instant.now())).as(customer)
  }

  private def seedEvent(labels: String*): IO[SeededEvent] =
    withSession(TicketSeed.event(List(SectionSpec("Parter", price, labels.toList.map(SeatLabel(_))))))

  private def hold(
    svc: TicketService,
    event: SeededEvent,
    customer: User,
    label: String
  ): IO[Ticket] =
    svc.holdSeats(HoldSeatsCommand(event.eventId, customer.id, List(event.seat(label).id))).flatMap {
      case HoldSeatsResult.SeatsHeld(_, tickets) => IO.pure(tickets.head)
      case other                                 => IO.raiseError(new AssertionError(s"expected SeatsHeld, got $other"))
    }

  // Held tickets whose hold has run out: exactly what cancelExpiredHolds is going to cancel.
  private val countExpiredHeld: Query[Void, Long] =
    sql"SELECT count(*) FROM ticket WHERE status = 'Held' AND held_until <= now()".query(int8)

  private def expiredHeldCount(): IO[Long] =
    withSession(summon[Session[IO]].unique(countExpiredHeld))

  private def statusOf(ticket: Ticket): IO[TicketStatus] =
    withSession(ticketRepo.find(ticket.id)).map(_.map(_.status).getOrElse(fail(s"ticket ${ticket.id} vanished")))

  "TicketService.cancelExpiredHolds" should {
    "cancel expired Held tickets and leave live ones, Paid and Cancelled ones untouched" in {
      for {
        customer  <- seedCustomer()
        event     <- seedEvent("A-1", "A-2", "A-3", "A-4")
        expired   <- hold(pastService, event, customer, "A-1")
        current   <- hold(service, event, customer, "A-2")
        paid      <- hold(pastService, event, customer, "A-3")
        cancelled <- hold(pastService, event, customer, "A-4")
        _         <- pastService.payTicket(PayTicketCommand(paid.id, customer.id, ChargeId("ch_expired_task")))
        _         <- transactor.inTransaction(ticketRepo.update(cancelled.id, _.cancel(Instant.now())))
        before    <- expiredHeldCount()
        count     <- service.cancelExpiredHolds()
        statuses  <- List(expired, current, paid, cancelled).traverse(statusOf)
      } yield {
        // At least our own expired ticket, and exactly what was expired before the run (nothing live, Paid or Cancelled).
        before should be >= 1L
        count.toLong shouldBe before
        statuses shouldBe List(TicketStatus.Cancelled, TicketStatus.Held, TicketStatus.Paid, TicketStatus.Cancelled)
      }
    }

    "free the seat of a cancelled hold so it can be held again, and be idempotent" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent("B-1")
        stale  <- hold(pastService, event, first, "B-1")
        before <- expiredHeldCount()
        run1   <- service.cancelExpiredHolds()
        run2   <- service.cancelExpiredHolds()
        again  <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("B-1").id)))
        status <- statusOf(stale)
      } yield {
        before should be >= 1L
        run1.toLong shouldBe before
        // Idempotent: the first run left no expired Held ticket, whatever other tests created.
        run2 shouldBe 0
        status shouldBe TicketStatus.Cancelled
        again shouldBe a[HoldSeatsResult.SeatsHeld]
      }
    }

    "do nothing when there are no expired holds" in {
      for {
        _      <- service.cancelExpiredHolds() // drain whatever other tests left behind
        second <- service.cancelExpiredHolds()
        left   <- expiredHeldCount()
      } yield {
        second shouldBe 0
        left shouldBe 0L
      }
    }
  }

  // holdSeats cancels the expired holds on the requested seats itself, without waiting for the task.
  "TicketService.holdSeats on seats with an expired hold" should {
    "succeed at once on a seat whose previous Held ticket expired, and cancel the old ticket" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent("D-1")
        stale  <- hold(pastService, event, first, "D-1")
        fresh  <- hold(service, event, second, "D-1")
        old    <- statusOf(stale)
        now    <- statusOf(fresh)
      } yield {
        old shouldBe TicketStatus.Cancelled
        now shouldBe TicketStatus.Held
      }
    }

    "still answer SeatAlreadyTaken for a seat whose Held ticket has not expired" in {
      for {
        first   <- seedCustomer()
        second  <- seedCustomer()
        event   <- seedEvent("E-1")
        current <- hold(service, event, first, "E-1")
        result  <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("E-1").id)))
        status  <- statusOf(current)
      } yield {
        result shouldBe HoldSeatsResult.SeatAlreadyTaken
        status shouldBe TicketStatus.Held
      }
    }

    "still answer SeatAlreadyTaken for a Paid ticket, even one whose hold time is long past" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent("F-1")
        paid   <- hold(pastService, event, first, "F-1")
        _      <- pastService.payTicket(PayTicketCommand(paid.id, first.id, ChargeId("ch_expired_paid")))
        result <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("F-1").id)))
        status <- statusOf(paid)
      } yield {
        result shouldBe HoldSeatsResult.SeatAlreadyTaken
        status shouldBe TicketStatus.Paid
      }
    }

    "keep the expired hold when the order fails on another seat (the cancellation rolls back)" in {
      for {
        first  <- seedCustomer()
        second <- seedCustomer()
        event  <- seedEvent("G-1", "G-2")
        stale  <- hold(pastService, event, first, "G-1")
        _      <- hold(service, event, first, "G-2")
        result <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("G-1").id, event.seat("G-2").id)))
        status <- statusOf(stale)
        // Clean up so that no expired Held ticket is left for other tests.
        _ <- service.cancelExpiredHolds()
      } yield {
        result shouldBe HoldSeatsResult.SeatAlreadyTaken
        status shouldBe TicketStatus.Held
      }
    }
  }

  "TicketService.cancelExpiredHoldsTask" should {
    "run the cancellation when executed" in {
      val task = service.cancelExpiredHoldsTask
      for {
        customer <- seedCustomer()
        event    <- seedEvent("C-1")
        stale    <- hold(pastService, event, customer, "C-1")
        _        <- task.execution(task)
        status   <- statusOf(stale)
      } yield status shouldBe TicketStatus.Cancelled
    }
  }
}
