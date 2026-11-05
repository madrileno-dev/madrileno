package madrileno.ticket.domain

import madrileno.user.domain.UserId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import java.util.UUID

class TicketSpec extends AnyWordSpec with Matchers {

  private val now          = Instant.parse("2026-01-01T10:00:00Z")
  private val holdDuration = Duration.ofMinutes(15)
  private val customerId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val stranger     = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val chargeId     = ChargeId("ch_123")
  private val price        = Money.from(5000L, Currency.PLN).getOrElse(fail("invalid price"))

  private val held: Ticket = Ticket.hold(
    id = TicketId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
    orderId = OrderId(UUID.fromString("00000000-0000-0000-0000-0000000000b1")),
    seatId = SeatId(UUID.fromString("00000000-0000-0000-0000-0000000000c1")),
    customerId = customerId,
    seatLabel = SeatLabel("A1"),
    price = price,
    now = now,
    holdDuration = holdDuration
  )
  private val heldUntil = held.heldUntil

  private val paid: Ticket =
    held.copy(status = TicketStatus.Paid, chargeId = Some(chargeId), paidAt = Some(now.plusSeconds(1)))
  private val cancelled: Ticket = held.copy(status = TicketStatus.Cancelled)

  "Ticket.hold" should {
    "create a Held ticket without charge or payment time" in {
      held.status shouldBe TicketStatus.Held
      held.chargeId shouldBe None
      held.paidAt shouldBe None
      held.customerId shouldBe customerId
    }

    "set heldUntil = now + holdDuration" in {
      held.heldUntil shouldBe now.plus(holdDuration)
    }

    "set createdAt = updatedAt = now" in {
      held.createdAt shouldBe now
      held.updatedAt shouldBe now
    }
  }

  "Ticket.pay" should {
    val later = now.plusSeconds(60)

    "mark a Held ticket as Paid with charge, paidAt and updatedAt" in {
      held.pay(customerId, chargeId, later) shouldBe
        Right(held.copy(status = TicketStatus.Paid, chargeId = Some(chargeId), paidAt = Some(later), updatedAt = later))
    }

    "succeed one instant before heldUntil" in {
      val result = held.pay(customerId, chargeId, heldUntil.minusNanos(1))
      result.map(_.status) shouldBe Right(TicketStatus.Paid)
    }

    "reject with HoldExpired exactly at heldUntil" in {
      held.pay(customerId, chargeId, heldUntil) shouldBe Left(PayRejection.HoldExpired)
    }

    "reject with HoldExpired after heldUntil" in {
      held.pay(customerId, chargeId, heldUntil.plusSeconds(1)) shouldBe Left(PayRejection.HoldExpired)
    }

    "reject with NotOwner when the requester is not the customer" in {
      held.pay(stranger, chargeId, later) shouldBe Left(PayRejection.NotOwner)
    }

    "reject with NotOwner even when the ticket is already paid" in {
      paid.pay(stranger, chargeId, later) shouldBe Left(PayRejection.NotOwner)
    }

    "reject with NotOwner even when the ticket is cancelled" in {
      cancelled.pay(stranger, chargeId, later) shouldBe Left(PayRejection.NotOwner)
    }

    "reject with NotOwner for a foreign user on an expired hold (authorization before time)" in {
      held.pay(stranger, chargeId, heldUntil.plusSeconds(1)) shouldBe Left(PayRejection.NotOwner)
    }

    "reject with AlreadyPaid for a paid ticket" in {
      paid.pay(customerId, chargeId, later) shouldBe Left(PayRejection.AlreadyPaid)
    }

    "reject with AlreadyPaid for a paid ticket past heldUntil (status before time)" in {
      paid.pay(customerId, chargeId, heldUntil.plusSeconds(1)) shouldBe Left(PayRejection.AlreadyPaid)
    }

    "reject with AlreadyCancelled for a cancelled ticket" in {
      cancelled.pay(customerId, chargeId, later) shouldBe Left(PayRejection.AlreadyCancelled)
    }

    "reject with AlreadyCancelled for a cancelled ticket past heldUntil (status before time)" in {
      cancelled.pay(customerId, chargeId, heldUntil.plusSeconds(1)) shouldBe Left(PayRejection.AlreadyCancelled)
    }

    "leave the original ticket untouched (also on rejection)" in {
      val before = held
      val _      = held.pay(customerId, chargeId, later)
      val _      = held.pay(stranger, chargeId, later)
      val _      = held.pay(customerId, chargeId, heldUntil)
      held shouldBe before
    }
  }

  "Ticket.cancel" should {
    val later = now.plusSeconds(60)

    "move a Held ticket to Cancelled and bump updatedAt" in {
      held.cancel(later) shouldBe Right(held.copy(status = TicketStatus.Cancelled, updatedAt = later))
    }

    "keep chargeId and paidAt empty" in {
      val result = held.cancel(later).getOrElse(fail("expected Right"))
      result.chargeId shouldBe None
      result.paidAt shouldBe None
    }

    "succeed on a Held ticket whose hold has already expired" in {
      val result = held.cancel(heldUntil.plusSeconds(3600))
      result.map(_.status) shouldBe Right(TicketStatus.Cancelled)
      result.map(_.updatedAt) shouldBe Right(heldUntil.plusSeconds(3600))
    }

    "reject with AlreadyPaid for a paid ticket" in {
      paid.cancel(later) shouldBe Left(CancelRejection.AlreadyPaid)
    }

    "reject with AlreadyCancelled for a cancelled ticket" in {
      cancelled.cancel(later) shouldBe Left(CancelRejection.AlreadyCancelled)
    }

    "leave the original ticket untouched (also on rejection)" in {
      val before = paid
      val _      = paid.cancel(later)
      paid shouldBe before
      val _ = held.cancel(later)
      held shouldBe Ticket.hold(held.id, held.orderId, held.seatId, customerId, held.seatLabel, price, now, holdDuration)
    }
  }
}
