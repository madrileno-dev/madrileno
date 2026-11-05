package madrileno.ticket.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SeatLabelSpec extends AnyWordSpec with Matchers {

  "SeatLabel" should {
    "accept non-empty strings and trim them" in {
      SeatLabel.from("  A1  ") shouldBe Right(SeatLabel("A1"))
    }

    "reject empty strings" in {
      SeatLabel.from("").isLeft shouldBe true
    }

    "reject whitespace-only strings" in {
      SeatLabel.from("   ").isLeft shouldBe true
    }
  }
}
