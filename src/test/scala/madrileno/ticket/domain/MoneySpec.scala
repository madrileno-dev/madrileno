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
}
