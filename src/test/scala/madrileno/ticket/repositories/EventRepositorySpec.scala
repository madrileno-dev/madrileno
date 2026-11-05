package madrileno.ticket.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{SectionSpec, TestData, TestTransactor, TicketSeed}
import madrileno.ticket.domain.*
import madrileno.user.repositories.UserRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

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
        found.map(_.sections.map(_.name)) shouldBe Some(List("Parter", "VIP"))
        found.map(_.sections.flatMap(_.seats.map(_.label))) shouldBe Some(List(SeatLabel("P-1"), SeatLabel("V-1"), SeatLabel("V-2")))
        found.map(_.sections.map(_.price)) shouldBe Some(List(parter, vip))
        found.map(e => (e.id, e.name, e.startsAt, e.status)) shouldBe Some((seeded.eventId, "Test event", startsAt, EventStatus.OnSale))
        found.map(_.sections.flatMap(_.seats).map(_.id).toSet) shouldBe Some(seeded.allSeats.map(_.id).toSet)
      }
    }

    "keep each seat in its own section" in withRollback {
      for {
        seeded <- TicketSeed.event(List(SectionSpec("VIP", vip, List(SeatLabel("V-1"))), SectionSpec("Parter", parter, List(SeatLabel("P-1")))))
        found  <- eventRepo.findWithSeats(seeded.eventId)
      } yield {
        val bySection = found.toList.flatMap(_.sections).map(s => s.name -> s.seats.map(_.label)).toMap
        bySection shouldBe Map("VIP" -> List(SeatLabel("V-1")), "Parter" -> List(SeatLabel("P-1")))
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
}
