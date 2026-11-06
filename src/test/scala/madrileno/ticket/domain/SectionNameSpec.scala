package madrileno.ticket.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SectionNameSpec extends AnyWordSpec with Matchers {

  "SectionName" should {
    "accept non-empty strings and trim them" in {
      SectionName.from("  VIP  ") shouldBe Right(SectionName("VIP"))
    }

    "reject empty strings" in {
      SectionName.from("").isLeft shouldBe true
    }

    "reject whitespace-only strings" in {
      SectionName.from("   ").isLeft shouldBe true
    }

    "compare case-sensitively, like the database constraint" in {
      SectionName("VIP") should not be SectionName("vip")
    }
  }
}
