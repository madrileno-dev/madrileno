package madrileno.auth.repositories

import madrileno.auth.domain.{RefreshTokenSecretHash, SealedRefreshTokenSecret}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RefreshTokenRedactionSpec extends AnyWordSpec with Matchers {

  "RefreshTokenRowTable" should {
    "redact the secret hash in telemetry" in {
      RefreshTokenRowTable.secretHash.c.encode(RefreshTokenSecretHash("hash")).flatten.map(_.redacted) shouldBe List(true)
    }

    "redact the sealed successor secret in telemetry" in {
      RefreshTokenRowTable.successor.c.encode(Some(SealedRefreshTokenSecret("sealed"))).flatten.map(_.redacted) shouldBe List(true)
    }

    "leave the user agent visible" in {
      RefreshTokenRowTable.userAgent.c.encode(madrileno.auth.domain.UserAgent("ua")).flatten.map(_.redacted) shouldBe List(false)
    }
  }
}
