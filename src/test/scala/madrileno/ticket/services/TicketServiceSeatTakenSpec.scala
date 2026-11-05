package madrileno.ticket.services

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.exception.PostgresErrorException

// Pure unit tests of the unique-violation translation; no database involved.
class TicketServiceSeatTakenSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  // 'C' = SQLSTATE, 'n' = constraint name, 'M' = message: the field codes of a Postgres ErrorResponse.
  private def pgError(code: String, constraint: Option[String]): PostgresErrorException =
    new PostgresErrorException("INSERT INTO ticket ...", None, Map('C' -> code, 'M' -> "boom") ++ constraint.map('n' -> _), Nil, Nil, None)

  "TicketService.isSeatTakenViolation" should {
    "accept only 23505 on ticket_live_seat_uidx" in {
      TicketService.isSeatTakenViolation("23505", Some("ticket_live_seat_uidx")) shouldBe true
      TicketService.isSeatTakenViolation("23505", Some("ticket_charge_id_uidx")) shouldBe false
      TicketService.isSeatTakenViolation("23505", None) shouldBe false
      TicketService.isSeatTakenViolation("23503", Some("ticket_live_seat_uidx")) shouldBe false
    }
  }

  "TicketService.seatTakenOrRethrow" should {
    "turn the live-seat unique violation into SeatAlreadyTaken" in {
      TicketService.seatTakenOrRethrow(pgError("23505", Some("ticket_live_seat_uidx"))).asserting(_ shouldBe HoldSeatsResult.SeatAlreadyTaken)
    }

    "rethrow a unique violation of another constraint" in {
      val error = pgError("23505", Some("ticket_charge_id_uidx"))
      TicketService.seatTakenOrRethrow(error).attempt.asserting(_ shouldBe Left(error))
    }

    "rethrow a Postgres error with another code" in {
      val error = pgError("23503", Some("ticket_live_seat_uidx"))
      TicketService.seatTakenOrRethrow(error).attempt.asserting(_ shouldBe Left(error))
    }

    "rethrow errors that are not Postgres errors" in {
      val error = new RuntimeException("unrelated")
      TicketService.seatTakenOrRethrow(error).attempt.asserting(_ shouldBe Left(error))
    }
  }

  "TicketService.toResult" should {
    "map every rejection to its own result" in IO {
      import madrileno.ticket.domain.HoldRejection
      HoldRejection.values.toList.map(r => r -> TicketService.toResult(r)) shouldBe List(
        HoldRejection.NoSeatsSelected -> HoldSeatsResult.NoSeatsSelected,
        HoldRejection.DuplicateSeats  -> HoldSeatsResult.DuplicateSeats,
        HoldRejection.EventNotOnSale  -> HoldSeatsResult.EventNotOnSale,
        HoldRejection.EventCancelled  -> HoldSeatsResult.EventCancelled,
        HoldRejection.SeatNotInEvent  -> HoldSeatsResult.SeatNotInEvent
      )
    }
  }
}
