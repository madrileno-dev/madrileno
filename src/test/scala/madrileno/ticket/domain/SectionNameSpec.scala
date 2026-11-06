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

    "accept exactly 200 characters and reject 201" in {
      SectionName.from("x" * 200).isRight shouldBe true
      SectionName.from("x" * 201).isLeft shouldBe true
    }

    "measure the length after trimming" in {
      SectionName.from(" " * 5 + "x" * 200 + " " * 5).isRight shouldBe true
    }

    "count a character outside the BMP once, like Postgres char_length" in {
      SectionName.from("\uD83D\uDE00" * 200).isRight shouldBe true
      SectionName.from("\uD83D\uDE00" * 201).isLeft shouldBe true
    }

    "reject a NUL character inside the text, which Postgres text cannot store" in {
      SectionName.from("ab\u0000cd").isLeft shouldBe true
    }

    "reject other control characters inside the text" in {
      SectionName.from("ab\ncd").isLeft shouldBe true
    }

    "strip a control character at the edge together with the whitespace" in {
      SectionName.from("\u0000abc\u0000").map(_.unwrap) shouldBe Right("abc")
    }
  }
}
