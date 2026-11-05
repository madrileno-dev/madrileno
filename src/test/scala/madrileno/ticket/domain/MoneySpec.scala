package madrileno.ticket.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MoneySpec extends AnyWordSpec with Matchers {

  "Money.from" should {
    "accept zero and positive amounts" in {
      Money.from(0L, Currency.PLN).map(_.amountMinor) shouldBe Right(0L)
      Money.from(1999L, Currency.EUR).map(_.amountMinor) shouldBe Right(1999L)
    }

    "reject negative amounts" in {
      Money.from(-1L, Currency.PLN).isLeft shouldBe true
    }
  }

  "Money.plus" should {
    "add amounts of the same currency" in {
      val result = for {
        a   <- Money.from(1000L, Currency.PLN)
        b   <- Money.from(250L, Currency.PLN)
        sum <- a.plus(b).left.map(_.toString)
      } yield sum
      result shouldBe Money.from(1250L, Currency.PLN)
    }

    "reject mismatched currencies" in {
      val result = for {
        a   <- Money.from(1000L, Currency.PLN)
        b   <- Money.from(250L, Currency.EUR)
        sum <- a.plus(b).left.map(_.toString)
      } yield sum
      result shouldBe Left(MoneyRejection.CurrencyMismatch.toString)
    }
  }
}
