package madrileno.auth.domain

import madrileno.support.TestData
import madrileno.user.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI

class UserAuthSpec extends AnyWordSpec with Matchers {

  "withUpdatedProfile" should {
    "fill in missing fields from the external provider" in {
      val user    = TestData.user(fullName = None, emailAddress = None, avatarUrl = None, emailVerified = false)
      val profile = ExternalProfile(
        fullName = Some(FullName("New Name")),
        emailAddress = Some(EmailAddress("new@example.com")),
        emailVerified = true,
        avatarUrl = Some(URI("https://example.com/avatar.png"))
      )

      val updated = user.withUpdatedProfile(profile)

      updated.fullName shouldBe Some(FullName("New Name"))
      updated.emailAddress shouldBe Some(EmailAddress("new@example.com"))
      updated.emailVerified shouldBe true
      updated.avatarUrl shouldBe Some(URI("https://example.com/avatar.png"))
    }

    "preserve existing fields when external has None" in {
      val user = TestData.user(
        fullName = Some(FullName("Existing")),
        emailAddress = Some(EmailAddress("existing@example.com")),
        avatarUrl = Some(URI("https://example.com/old.png"))
      )
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = false, avatarUrl = None)

      val updated = user.withUpdatedProfile(profile)

      updated.fullName shouldBe Some(FullName("Existing"))
      updated.emailAddress shouldBe Some(EmailAddress("existing@example.com"))
      updated.avatarUrl shouldBe Some(URI("https://example.com/old.png"))
    }

    "keep emailVerified sticky (true stays true)" in {
      val user    = TestData.user(emailVerified = true)
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = false, avatarUrl = None)

      user.withUpdatedProfile(profile).emailVerified shouldBe true
    }

    "upgrade emailVerified from false to true" in {
      val user    = TestData.user(emailVerified = false)
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = true, avatarUrl = None)

      user.withUpdatedProfile(profile).emailVerified shouldBe true
    }
  }

  "VerifiedExternalToken.toUser" should {
    "create an unblocked user from the token's profile" in {
      val token = TestData.verifiedExternalToken()
      val id    = TestData.randomUserId()

      val user = token.toUser(id)

      user.id shouldBe id
      user.fullName shouldBe token.profile.fullName
      user.emailAddress shouldBe token.profile.emailAddress
      user.emailVerified shouldBe token.profile.emailVerified
      user.avatarUrl shouldBe token.profile.avatarUrl
      user.isActive shouldBe true
    }
  }
}
