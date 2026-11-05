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

import java.time.Instant
import scala.concurrent.duration.*

// Every suite gets its own Postgres container, so the number of cancelled tickets can be asserted exactly.
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
        count     <- service.cancelExpiredHolds()
        statuses  <- List(expired, current, paid, cancelled).traverse(statusOf)
      } yield {
        count shouldBe 1
        statuses shouldBe List(TicketStatus.Cancelled, TicketStatus.Held, TicketStatus.Paid, TicketStatus.Cancelled)
      }
    }

    "free the seat of a cancelled hold so it can be held again, and be idempotent" in {
      for {
        first   <- seedCustomer()
        second  <- seedCustomer()
        event   <- seedEvent("B-1")
        stale   <- hold(pastService, event, first, "B-1")
        blocked <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("B-1").id)))
        run1    <- service.cancelExpiredHolds()
        run2    <- service.cancelExpiredHolds()
        again   <- service.holdSeats(HoldSeatsCommand(event.eventId, second.id, List(event.seat("B-1").id)))
        status  <- statusOf(stale)
      } yield {
        // Until the task runs, the expired Held row still blocks the seat.
        blocked shouldBe HoldSeatsResult.SeatAlreadyTaken
        run1 shouldBe 1
        run2 shouldBe 0
        status shouldBe TicketStatus.Cancelled
        again shouldBe a[HoldSeatsResult.SeatsHeld]
      }
    }

    "do nothing when there are no expired holds" in {
      service.cancelExpiredHolds().asserting(_ shouldBe 0)
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
