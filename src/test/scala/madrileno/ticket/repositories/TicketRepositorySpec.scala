package madrileno.ticket.repositories

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import madrileno.support.{SeededSeat, TestData, TestTransactor, TicketSeed}
import madrileno.ticket.domain.*
import madrileno.user.domain.User
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.DB
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.codec.all.*
import skunk.exception.PostgresErrorException
import skunk.implicits.*
import skunk.{Command, Session}

import java.time.{Duration, Instant}
import java.util.UUID

class TicketRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo     = new TicketRepository
  private lazy val userRepo = new UserRepository

  private val now          = Instant.parse("2026-01-01T10:00:00Z")
  private val holdDuration = Duration.ofMinutes(15)

  private def seedCustomerAndSeat(seatLabel: SeatLabel = SeatLabel("A-1")): DB[(User, SeededSeat)] = {
    val customer = TestData.user()
    for {
      _    <- userRepo.create(customer, now)
      seat <- TicketSeed.seat(customer.id, seatLabel)
    } yield (customer, seat)
  }

  private def hold(
    customer: User,
    seat: SeededSeat,
    at: Instant = now
  ): Ticket =
    Ticket.hold(TestData.randomTicketId(), seat.orderId, seat.seatId, customer.id, seat.seatLabel, seat.price, at, holdDuration)

  private def paid(ticket: Ticket, chargeId: ChargeId): Ticket =
    ticket.pay(ticket.customerId, chargeId, now.plusSeconds(60)).fold(rejection => fail(s"pay rejected: $rejection"), identity)

  // The failing statement aborts the surrounding transaction, so it must be the last one in the test body.
  private def assertPgError(
    result: Either[Throwable, Unit],
    sqlState: String,
    constraint: String
  ): Assertion =
    result match {
      case Left(e: PostgresErrorException) =>
        e.code shouldBe sqlState
        e.constraintName shouldBe Some(constraint)
      case other => fail(s"expected PostgresErrorException $sqlState on $constraint, got $other")
    }

  "The text length CHECK constraints of ticket" should {
    val overLong = "x" * 201
    val atLimit  = "x" * 200

    // Plain SQL on an existing ticket: the opaque types refuse over-long values, so the domain cannot even build them.
    val setSeatLabel: Command[(String, UUID)] = sql"UPDATE ticket SET seat_label = $text WHERE id = $uuid".command
    val setCharge: Command[(String, UUID)]    =
      sql"UPDATE ticket SET status = 'Paid', charge_id = $text, paid_at = now() WHERE id = $uuid".command

    "accept a 200 character seat label and reject 201 (ticket.seat_label)" in withRollback {
      val session = summon[Session[IO]]
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = hold(customer, seat)
        _      <- repo.save(ticket)
        _      <- session.execute(setSeatLabel)((atLimit, ticket.id.unwrap))
        result <- session.execute(setSeatLabel)((overLong, ticket.id.unwrap)).attempt
      } yield assertPgError(result.void, "23514", "ticket_seat_label_check")
    }

    "accept a 200 character charge id and reject 201 (ticket.charge_id)" in withRollback {
      val session = summon[Session[IO]]
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = hold(customer, seat)
        _      <- repo.save(ticket)
        _      <- session.execute(setCharge)((atLimit, ticket.id.unwrap))
        result <- session.execute(setCharge)((overLong, ticket.id.unwrap)).attempt
      } yield assertPgError(result.void, "23514", "ticket_charge_id_check")
    }
  }

  "TicketRepository" should {
    "save and find a held ticket" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = hold(customer, seat)
        _       <- repo.save(ticket)
        fetched <- repo.find(ticket.id)
      } yield fetched shouldBe Some(ticket)
    }

    "round-trip a paid ticket with price, currency, status, chargeId and paidAt" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = paid(hold(customer, seat).copy(price = TestData.ticketPrice(4_999L, Currency.EUR)), ChargeId("ch_round_trip"))
        _       <- repo.save(ticket)
        fetched <- repo.find(ticket.id)
      } yield {
        fetched shouldBe Some(ticket)
        fetched.map(_.price) shouldBe Some(TestData.ticketPrice(4_999L, Currency.EUR))
        fetched.map(_.status) shouldBe Some(TicketStatus.Paid)
        fetched.flatMap(_.chargeId) shouldBe Some(ChargeId("ch_round_trip"))
      }
    }

    "find returns None for a non-existent ticket" in withRollback {
      repo.find(TestData.randomTicketId()).map(_ shouldBe None)
    }

    "update applies the transition and persists the new aggregate" in withRollback {
      val payAt = now.plusSeconds(60)
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = hold(customer, seat)
        _       <- repo.save(ticket)
        result  <- repo.update(ticket.id, _.pay(customer.id, ChargeId("ch_update"), payAt))
        fetched <- repo.find(ticket.id)
      } yield {
        result.map(_.map(_.status)) shouldBe Some(Right(TicketStatus.Paid))
        fetched shouldBe result.flatMap(_.toOption)
        fetched.flatMap(_.chargeId) shouldBe Some(ChargeId("ch_update"))
        fetched.flatMap(_.paidAt) shouldBe Some(payAt)
        fetched.map(_.updatedAt) shouldBe Some(payAt)
      }
    }

    "update returns the rejection without persisting" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        ticket = hold(customer, seat)
        _       <- repo.save(ticket)
        result  <- repo.update(ticket.id, _.pay(TestData.randomUserId(), ChargeId("ch_foreign"), now.plusSeconds(60)))
        fetched <- repo.find(ticket.id)
      } yield {
        result shouldBe Some(Left(PayRejection.NotOwner))
        fetched shouldBe Some(ticket)
      }
    }

    "update returns None for a non-existent ticket" in withRollback {
      repo.update(TestData.randomTicketId(), _.cancel(now)).map(_ shouldBe None)
    }

    "listExpiredHolds returns only Held tickets whose hold ended at or before now" in withRollback {
      val checkAt = now.plus(holdDuration)
      for {
        (customer, seatA) <- seedCustomerAndSeat(SeatLabel("A-1"))
        seatB             <- TicketSeed.seat(customer.id, SeatLabel("B-1"))
        seatC             <- TicketSeed.seat(customer.id, SeatLabel("C-1"))
        seatD             <- TicketSeed.seat(customer.id, SeatLabel("D-1"))
        seatE             <- TicketSeed.seat(customer.id, SeatLabel("E-1"))
        expiredEarlier = hold(customer, seatA, at = now.minusSeconds(60))
        expiredExactly = hold(customer, seatB, at = now)
        stillHeld      = hold(customer, seatC, at = now.plusSeconds(1))
        paidTicket     = paid(hold(customer, seatD, at = now.minusSeconds(60)), ChargeId("ch_expiry"))
        cancelled      = hold(customer, seatE, at = now.minusSeconds(60)).cancel(now).fold(r => fail(s"cancel rejected: $r"), identity)
        _       <- List(expiredEarlier, expiredExactly, stillHeld, paidTicket, cancelled).traverse_(t => repo.save(t))
        expired <- repo.listExpiredHolds(checkAt)
      } yield expired should contain theSameElementsAs List(expiredEarlier.id, expiredExactly.id)
    }

    "listExpiredHoldsForSeats returns only expired Held tickets on the given seats" in withRollback {
      val checkAt = now.plus(holdDuration)
      for {
        (customer, seatA) <- seedCustomerAndSeat(SeatLabel("A-1"))
        seatB             <- TicketSeed.seat(customer.id, SeatLabel("B-1"))
        seatC             <- TicketSeed.seat(customer.id, SeatLabel("C-1"))
        seatD             <- TicketSeed.seat(customer.id, SeatLabel("D-1"))
        expiredOnA = hold(customer, seatA, at = now.minusSeconds(60))
        expiredOnB = hold(customer, seatB, at = now.minusSeconds(60))
        stillHeld  = hold(customer, seatC, at = now.plusSeconds(1))
        paidTicket = paid(hold(customer, seatD, at = now.minusSeconds(60)), ChargeId("ch_for_seats"))
        _     <- List(expiredOnA, expiredOnB, stillHeld, paidTicket).traverse_(t => repo.save(t))
        found <- repo.listExpiredHoldsForSeats(List(seatA.seatId, seatC.seatId, seatD.seatId), checkAt)
        none  <- repo.listExpiredHoldsForSeats(Nil, checkAt)
      } yield {
        found shouldBe List(expiredOnA.id)
        none shouldBe Nil
      }
    }

    "allow a new hold on a seat whose previous ticket was cancelled" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        cancelled = hold(customer, seat).cancel(now).fold(r => fail(s"cancel rejected: $r"), identity)
        rehold    = hold(customer, seat, at = now.plusSeconds(5))
        _        <- repo.save(cancelled)
        _        <- repo.save(rehold)
        fetched1 <- repo.find(cancelled.id)
        fetched2 <- repo.find(rehold.id)
      } yield {
        fetched1.map(_.status) shouldBe Some(TicketStatus.Cancelled)
        fetched2.map(_.status) shouldBe Some(TicketStatus.Held)
      }
    }

    "reject a second Held ticket for a seat that is already held (ticket_live_seat_uidx)" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        _                <- repo.save(hold(customer, seat))
        result           <- repo.save(hold(customer, seat)).attempt
      } yield assertPgError(result, "23505", "ticket_live_seat_uidx")
    }

    "reject a new hold for a seat whose ticket is Paid (ticket_live_seat_uidx)" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        _                <- repo.save(paid(hold(customer, seat), ChargeId("ch_seat_taken")))
        result           <- repo.save(hold(customer, seat)).attempt
      } yield assertPgError(result, "23505", "ticket_live_seat_uidx")
    }

    "reject the same chargeId on two tickets (ticket_charge_id_uidx)" in withRollback {
      for {
        (customer, seatA) <- seedCustomerAndSeat(SeatLabel("A-1"))
        seatB             <- TicketSeed.seat(customer.id, SeatLabel("B-1"))
        _                 <- repo.save(paid(hold(customer, seatA), ChargeId("ch_shared")))
        result            <- repo.save(paid(hold(customer, seatB), ChargeId("ch_shared"))).attempt
      } yield assertPgError(result, "23505", "ticket_charge_id_uidx")
    }

    "reject a Paid ticket without chargeId (ticket_paid_iff_charged_chk)" in withRollback {
      for {
        (customer, seat) <- seedCustomerAndSeat()
        inconsistent = TestData.ticket(
                         orderId = seat.orderId,
                         seatId = seat.seatId,
                         customerId = customer.id,
                         status = TicketStatus.Paid,
                         chargeId = None,
                         paidAt = Some(now)
                       )
        result <- repo.save(inconsistent).attempt
      } yield assertPgError(result, "23514", "ticket_paid_iff_charged_chk")
    }
  }
}
