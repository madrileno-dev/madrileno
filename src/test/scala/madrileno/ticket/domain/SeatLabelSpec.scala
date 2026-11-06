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

    "accept exactly 200 characters and reject 201" in {
      SeatLabel.from("x" * 200).isRight shouldBe true
      SeatLabel.from("x" * 201).isLeft shouldBe true
    }

    "measure the length after trimming" in {
      SeatLabel.from(" " * 5 + "x" * 200 + " " * 5).isRight shouldBe true
    }

    "count a character outside the BMP once, like Postgres char_length" in {
      SeatLabel.from("\uD83D\uDE00" * 200).isRight shouldBe true
      SeatLabel.from("\uD83D\uDE00" * 201).isLeft shouldBe true
    }

    "reject a NUL character inside the text, which Postgres text cannot store" in {
      SeatLabel.from("ab\u0000cd").isLeft shouldBe true
    }

    "reject other control characters inside the text" in {
      SeatLabel.from("ab\ncd").isLeft shouldBe true
    }

    "strip a control character at the edge together with the whitespace" in {
      SeatLabel.from("\u0000abc\u0000").map(_.unwrap) shouldBe Right("abc")
    }
  }
}
