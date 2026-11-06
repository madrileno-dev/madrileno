package madrileno.ticket.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EventNameSpec extends AnyWordSpec with Matchers {

  "EventName" should {
    "accept non-empty strings and trim them" in {
      EventName.from("  Concert  ") shouldBe Right(EventName("Concert"))
    }

    "reject empty strings" in {
      EventName.from("").isLeft shouldBe true
    }

    "reject whitespace-only strings" in {
      EventName.from("   ").isLeft shouldBe true
    }
  }
}
