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

    "accept exactly 200 characters and reject 201" in {
      EventName.from("x" * 200).isRight shouldBe true
      EventName.from("x" * 201).isLeft shouldBe true
    }

    "measure the length after trimming" in {
      EventName.from(" " * 5 + "x" * 200 + " " * 5).isRight shouldBe true
    }

    "count a character outside the BMP once, like Postgres char_length" in {
      EventName.from("\uD83D\uDE00" * 200).isRight shouldBe true
      EventName.from("\uD83D\uDE00" * 201).isLeft shouldBe true
    }

    "reject a NUL character inside the text, which Postgres text cannot store" in {
      EventName.from("ab\u0000cd").isLeft shouldBe true
    }

    "reject other control characters inside the text" in {
      EventName.from("ab\ncd").isLeft shouldBe true
    }

    "strip a control character at the edge together with the whitespace" in {
      EventName.from("\u0000abc\u0000").map(_.unwrap) shouldBe Right("abc")
    }
  }
}
