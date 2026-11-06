package madrileno.ticket.repositories

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{SectionSpec, TestData, TestTransactor, TicketSeed}
import madrileno.ticket.domain.*
import madrileno.user.repositories.UserRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.codec.all.*
import skunk.exception.PostgresErrorException
import skunk.implicits.*
import skunk.{Command, Session}

import java.time.Instant
import java.util.UUID

class EventRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val eventRepo  = new EventRepository
  private lazy val orderRepo  = new OrderRepository
  private lazy val ticketRepo = new TicketRepository
  private lazy val userRepo   = new UserRepository

  private val now    = Instant.parse("2026-01-01T10:00:00Z")
  private val vip    = TestData.ticketPrice(30_000L, Currency.EUR)
  private val parter = TestData.ticketPrice(8_000L)

  "EventRepository.findWithSeats" should {
    "return the event with its sections, their prices and their seats" in withRollback {
      val startsAt = Instant.parse("2026-06-01T18:30:00Z")
      for {
        seeded <- TicketSeed.event(
                    List(SectionSpec("VIP", vip, List(SeatLabel("V-2"), SeatLabel("V-1"))), SectionSpec("Parter", parter, List(SeatLabel("P-1")))),
                    startsAt = startsAt
                  )
        found <- eventRepo.findWithSeats(seeded.eventId)
      } yield {
        // Sections and seats come back sorted by name / label.
        found.map(_.sections.map(_.name)) shouldBe Some(List(SectionName("Parter"), SectionName("VIP")))
        found.map(_.sections.flatMap(_.seats.map(_.label))) shouldBe Some(List(SeatLabel("P-1"), SeatLabel("V-1"), SeatLabel("V-2")))
        found.map(_.sections.map(_.price)) shouldBe Some(List(parter, vip))
        found.map(e => (e.id, e.name, e.startsAt, e.status)) shouldBe Some((seeded.eventId, EventName("Test event"), startsAt, EventStatus.OnSale))
        found.map(_.sections.flatMap(_.seats).map(_.id).toSet) shouldBe Some(seeded.allSeats.map(_.id).toSet)
      }
    }

    "keep each seat in its own section" in withRollback {
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1"))), SectionSpec("Parter", parter, List(SeatLabel("P-1")))))
        found  <- eventRepo.findWithSeats(seeded.eventId)
      } yield {
        val bySection = found.toList.flatMap(_.sections).map(s => s.name -> s.seats.map(_.label)).toMap
        bySection shouldBe Map(SectionName("VIP") -> List(SeatLabel("V-1")), SectionName("Parter") -> List(SeatLabel("P-1")))
      }
    }

    "return a cancelled event with its status" in withRollback {
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1")))), status = EventStatus.Cancelled)
        found  <- eventRepo.findWithSeats(seeded.eventId)
      } yield found.map(_.status) shouldBe Some(EventStatus.Cancelled)
    }

    "return an event without sections as an event with no sections" in withRollback {
      for {
        seeded <- TicketSeed.event(Nil)
        found  <- eventRepo.findWithSeats(seeded.eventId)
      } yield found.map(_.sections) shouldBe Some(Nil)
    }

    "return None for an unknown event" in withRollback {
      eventRepo.findWithSeats(TestData.randomEventId()).map(_ shouldBe None)
    }
  }

  "EventRepository.addSection" should {
    def seatIds(labels: List[SeatLabel]): Map[SeatLabel, SeatId] = labels.map(_ -> TestData.randomSeatId()).toMap

    "persist the section and its seats so that findWithSeats returns them" in withRollback {
      val labels    = List(SeatLabel("B-2"), SeatLabel("B-1"))
      val ids       = seatIds(labels)
      val sectionId = TestData.randomSectionId()
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1")))))
        added  <- eventRepo.addSection(
                   seeded.eventId,
                   _.addSection(sectionId, ids, SectionName("Balcony"), parter, labels, now.minusSeconds(86400 * 365 * 5))
                 )
        found <- eventRepo.findWithSeats(seeded.eventId)
      } yield {
        val (updated, section) = added.getOrElse(fail("event not found")).getOrElse(fail("rejected"))
        section.seats.map(_.id) shouldBe labels.map(ids)
        // Sections come back sorted by name, seats by label.
        found.map(_.sections.map(_.name)) shouldBe Some(List(SectionName("Balcony"), SectionName("VIP")))
        found.flatMap(_.sections.headOption) shouldBe Some(
          EventSection(
            sectionId,
            SectionName("Balcony"),
            parter,
            List(EventSeat(ids(SeatLabel("B-1")), SeatLabel("B-1")), EventSeat(ids(SeatLabel("B-2")), SeatLabel("B-2")))
          )
        )
        updated.sections.map(_.id).toSet shouldBe found.map(_.sections.map(_.id).toSet).getOrElse(Set.empty)
      }
    }

    "write nothing when the domain rejects the section" in withRollback {
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1")))))
        labels = List(SeatLabel("V-9"))
        result <- eventRepo.addSection(
                    seeded.eventId,
                    _.addSection(TestData.randomSectionId(), seatIds(labels), SectionName("VIP"), parter, labels, now.minusSeconds(86400 * 365 * 5))
                  )
        found <- eventRepo.findWithSeats(seeded.eventId)
      } yield {
        result shouldBe Some(Left(AddSectionRejection.SectionNameTaken))
        found.map(_.sections.map(_.name)) shouldBe Some(List(SectionName("VIP")))
        found.map(_.sections.flatMap(_.seats.map(_.label))) shouldBe Some(List(SeatLabel("V-1")))
      }
    }

    "return None for an unknown event" in withRollback {
      eventRepo.addSection(TestData.randomEventId(), _ => Left(AddSectionRejection.NoSeats)).map(_ shouldBe None)
    }
  }

  "OrderRepository.save" should {
    "persist an order so that its tickets can reference it" in withRollback {
      val customer = TestData.user()
      for {
        _      <- userRepo.create(customer, now)
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1")))))
        seat  = seeded.seat("V-1")
        order = Order(TestData.randomOrderId(), seeded.eventId, customer.id, now)
        _ <- orderRepo.save(order)
        ticket = Ticket.hold(TestData.randomTicketId(), order.id, seat.id, customer.id, seat.label, vip, now, java.time.Duration.ofMinutes(10))
        _      <- ticketRepo.save(ticket)
        stored <- ticketRepo.find(ticket.id)
      } yield stored shouldBe Some(ticket)
    }

    "fail for an unknown customer (foreign key)" in withRollback {
      for {
        seeded <- TicketSeed.event(Nil)
        result <- orderRepo.save(Order(TestData.randomOrderId(), seeded.eventId, TestData.randomUserId(), now)).attempt
      } yield result.isLeft shouldBe true
    }
  }

  "EventRepository.save" should {
    "round-trip a new event, with no sections, and keep the instant" in withRollback {
      val startsAt = Instant.parse("2030-12-01T18:30:00Z")
      val created  = Instant.parse("2026-01-01T10:00:00Z")
      val event    = Event(TestData.randomEventId(), EventName("Concert"), startsAt, EventStatus.OnSale, created, created, Nil)
      for {
        _    <- eventRepo.save(event)
        full <- eventRepo.findWithSeats(event.id)
      } yield full shouldBe Some(event)
    }
  }

  // The domain limits free text to 200 characters (TextRules); the CHECK constraints are the backstop for anything
  // that bypasses it. check_violation is SQLSTATE 23514. The failing statement aborts the transaction, so it must be last.
  private def assertCheckViolation(result: Either[Throwable, Any], constraint: String) =
    result match {
      case Left(e: PostgresErrorException) =>
        e.code shouldBe "23514"
        e.constraintName shouldBe Some(constraint)
      case other => fail(s"expected check violation on $constraint, got $other")
    }

  "The text length CHECK constraints" should {
    val overLong = "x" * 201
    val atLimit  = "x" * 200
    // Plain SQL: the opaque types refuse over-long values, so the domain cannot even build them.
    val insertEvent: Command[(UUID, String)] =
      sql"""INSERT INTO event (id, name, starts_at, status, created_at, updated_at)
            VALUES ($uuid, $text, now() + interval '1 day', 'OnSale', now(), now())""".command
    val insertSeat: Command[(UUID, UUID, String)] =
      sql"INSERT INTO event_seat (id, section_id, label) VALUES ($uuid, $uuid, $text)".command

    "accept a 200 character event name and reject 201 (event.name)" in withRollback {
      val session = summon[Session[IO]]
      for {
        _      <- session.execute(insertEvent)((TestData.randomUuid(), atLimit))
        result <- session.execute(insertEvent)((TestData.randomUuid(), overLong)).attempt
      } yield assertCheckViolation(result, "event_name_check")
    }

    "accept a 200 character section name and reject 201 (event_section.name)" in withRollback {
      for {
        seeded <- TicketSeed.event(List(SectionSpec(atLimit, vip, List(SeatLabel("V-1")))))
        result <- TicketSeed.event(List(SectionSpec(overLong, vip, List(SeatLabel("V-1"))))).attempt
      } yield {
        seeded.sections.map(_.name.unwrap.length) shouldBe List(200)
        assertCheckViolation(result, "event_section_name_check")
      }
    }

    "accept a 200 character seat label and reject 201 (event_seat.label)" in withRollback {
      val session = summon[Session[IO]]
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1")))))
        sectionId = seeded.sections.head.id.unwrap
        _      <- session.execute(insertSeat)((TestData.randomUuid(), sectionId, atLimit))
        result <- session.execute(insertSeat)((TestData.randomUuid(), sectionId, overLong)).attempt
      } yield assertCheckViolation(result, "event_seat_label_check")
    }
  }
}
