package madrileno.ticket.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ChargeIdSpec extends AnyWordSpec with Matchers {

  "ChargeId" should {
    "accept non-empty strings and trim them" in {
      ChargeId.from("  A1  ") shouldBe Right(ChargeId("A1"))
    }

    "reject empty strings" in {
      ChargeId.from("").isLeft shouldBe true
    }

    "reject whitespace-only strings" in {
      ChargeId.from("   ").isLeft shouldBe true
    }
  }
}
