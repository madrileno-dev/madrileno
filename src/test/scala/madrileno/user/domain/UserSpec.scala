package madrileno.user.domain

import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class UserSpec extends AnyWordSpec with Matchers {

  "User" should {
    "be active when not blocked" in {
      TestData.user().isActive shouldBe true
    }

    "be inactive when blocked" in {
      TestData.user(blockedAt = Some(Instant.now())).isActive shouldBe false
    }
  }

  "EmailAddress" should {
    "accept valid addresses" in {
      EmailAddress("user@example.com").toString shouldBe "user@example.com"
    }

    "reject strings without @" in {
      assertThrows[IllegalArgumentException](EmailAddress("invalid"))
    }

    "reject too-short strings" in {
      assertThrows[IllegalArgumentException](EmailAddress("a@"))
    }
  }

  "FullName" should {
    "accept non-empty strings" in {
      FullName("Alice").toString shouldBe "Alice"
    }

    "trim whitespace" in {
      FullName("  Bob  ").toString shouldBe "Bob"
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException](FullName(""))
    }
  }
}
