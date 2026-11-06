package madrileno.ticket.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestData, TestGivens, TestTransactor}
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

// Commits for real (the service opens its own transactions). Every test creates its own event through the service.
class TicketServiceSectionsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Clock[IO]        = IO.asyncForIO
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  given UUIDGen[IO]      = UUIDGen.fromSecureRandom[IO](using IO.asyncForIO, TestGivens.secureRandom)

  private lazy val userRepo = new UserRepository
  private lazy val service  =
    new TicketService(new TicketRepository, new EventRepository, new OrderRepository, TicketConfig(10.minutes), transactor)

  private val price = TestData.ticketPrice(12_000L)

  private def seedCustomer(): IO[User] = {
    val customer = TestData.user()
    withSession(userRepo.create(customer, Instant.now())).as(customer)
  }

  private def createEvent(startsAt: Instant = Instant.now().plusSeconds(86400)): IO[Event] =
    service.createEvent(CreateEventCommand(EventName("Concert"), startsAt)).map {
      case CreateEventResult.EventCreated(event) => event
      case other                                 => fail(s"unexpected $other")
    }

  private def addCommand(
    event: Event,
    name: String = "Parter",
    amount: Money = price,
    labels: List[String] = List("A-1", "A-2")
  ): AddSectionCommand =
    AddSectionCommand(event.id, SectionName(name), amount, labels.map(SeatLabel(_)))

  private def added(result: AddSectionResult): (Event, EventSection) =
    result match {
      case AddSectionResult.SectionAdded(event, section) => (event, section)
      case other                                         => fail(s"unexpected $other")
    }

  private val countSections: Query[UUID, Long] =
    sql"SELECT count(*) FROM event_section WHERE event_id = $uuid".query(int8)

  private val countSeats: Query[UUID, Long] =
    sql"SELECT count(*) FROM event_seat s JOIN event_section e ON e.id = s.section_id WHERE e.event_id = $uuid".query(int8)

  "TicketService.addSection" should {
    "persist the section and its seats, and getEvent returns the seating plan" in {
      for {
        event  <- createEvent()
        result <- service.addSection(addCommand(event, labels = List("A-2", "A-1")))
        (updated, section) = added(result)
        found <- service.getEvent(event.id)
      } yield {
        section.name shouldBe SectionName("Parter")
        section.price shouldBe price
        section.seats.map(_.label) shouldBe List(SeatLabel("A-2"), SeatLabel("A-1"))
        updated.sections shouldBe List(section)
        // findWithSeats sorts seats by label, so compare as sets of (id, label).
        found.map(_.sections.map(s => (s.id, s.name, s.price, s.seats.toSet))) shouldBe Some(
          List((section.id, section.name, price, section.seats.toSet))
        )
      }
    }

    "report an unknown event" in {
      service
        .addSection(AddSectionCommand(TestData.randomEventId(), SectionName("Parter"), price, List(SeatLabel("A-1"))))
        .asserting(_ shouldBe AddSectionResult.EventNotFound)
    }

    "report every rejection and write nothing" in {
      for {
        event           <- createEvent()
        started         <- createEvent(Instant.now().plusMillis(300))
        _               <- IO.sleep(500.millis)
        _               <- service.addSection(addCommand(event, name = "VIP")).map(added)
        noSeats         <- service.addSection(addCommand(event, labels = Nil))
        dupes           <- service.addSection(addCommand(event, labels = List("A-1", "A-1")))
        free            <- service.addSection(addCommand(event, amount = Money.from(0L, Currency.PLN).getOrElse(fail("money"))))
        taken           <- service.addSection(addCommand(event, name = "VIP"))
        notOnSale       <- service.addSection(addCommand(started))
        sections        <- withSession(summon[Session[IO]].unique(countSections)(event.id.unwrap))
        seats           <- withSession(summon[Session[IO]].unique(countSeats)(event.id.unwrap))
        startedSections <- withSession(summon[Session[IO]].unique(countSections)(started.id.unwrap))
      } yield {
        noSeats shouldBe AddSectionResult.NoSeats
        dupes shouldBe AddSectionResult.DuplicateSeatLabels
        free shouldBe AddSectionResult.PriceMustBePositive
        taken shouldBe AddSectionResult.SectionNameTaken
        notOnSale shouldBe AddSectionResult.EventNotOnSale
        // Only the successful VIP section (two seats) exists.
        (sections, seats, startedSections) shouldBe (1L, 2L, 0L)
      }
    }

    "report a cancelled event" in {
      for {
        event  <- createEvent()
        _      <- withSession(summon[Session[IO]].execute(sql"UPDATE event SET status = 'Cancelled' WHERE id = $uuid".command)(event.id.unwrap))
        result <- service.addSection(addCommand(event))
      } yield result shouldBe AddSectionResult.EventCancelled
    }

    "let exactly one of two parallel additions of the same section name win, never failing with an exception" in {
      for {
        event    <- createEvent()
        results  <- (1 to 4).toList.parTraverse(_ => service.addSection(addCommand(event, name = "Race")).attempt)
        sections <- withSession(summon[Session[IO]].unique(countSections)(event.id.unwrap))
      } yield {
        results.collect { case Left(error) => error } shouldBe Nil
        results.collect { case Right(AddSectionResult.SectionAdded(_, _)) => 1 }.size shouldBe 1
        results.collect { case Right(AddSectionResult.SectionNameTaken) => 1 }.size shouldBe 3
        sections shouldBe 1L
      }
    }

    "make the returned seats holdable: create event, add section, hold" in {
      for {
        customer     <- seedCustomer()
        event        <- createEvent()
        (_, section) <- service.addSection(addCommand(event)).map(added)
        held         <- service.holdSeats(HoldSeatsCommand(event.id, customer.id, section.seats.map(_.id)))
      } yield held match {
        case HoldSeatsResult.SeatsHeld(order, tickets) =>
          order.eventId shouldBe event.id
          tickets.map(_.seatLabel) shouldBe section.seats.map(_.label)
          tickets.map(_.price).distinct shouldBe List(price)
        case other => fail(s"unexpected $other")
      }
    }
  }
}
