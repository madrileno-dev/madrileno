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

    "accept exactly 200 characters and reject 201" in {
      ChargeId.from("x" * 200).isRight shouldBe true
      ChargeId.from("x" * 201).isLeft shouldBe true
    }

    "measure the length after trimming" in {
      ChargeId.from(" " * 5 + "x" * 200 + " " * 5).isRight shouldBe true
    }

    "count a character outside the BMP once, like Postgres char_length" in {
      ChargeId.from("\uD83D\uDE00" * 200).isRight shouldBe true
      ChargeId.from("\uD83D\uDE00" * 201).isLeft shouldBe true
    }

    "reject a NUL character inside the text, which Postgres text cannot store" in {
      ChargeId.from("ab\u0000cd").isLeft shouldBe true
    }

    "reject other control characters inside the text" in {
      ChargeId.from("ab\ncd").isLeft shouldBe true
    }

    "strip a control character at the edge together with the whitespace" in {
      ChargeId.from("\u0000abc\u0000").map(_.unwrap) shouldBe Right("abc")
    }
  }
}
