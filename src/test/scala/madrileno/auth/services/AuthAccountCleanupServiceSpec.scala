package madrileno.auth.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import madrileno.auth.domain.*
import madrileno.auth.repositories.*
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.events.outbox.Reaction
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant

class AuthAccountCleanupServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val now         = Instant.parse("2026-06-28T10:00:00Z")
  private given Clock[IO] = TestGivens.fixedClock(now)

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private lazy val userRepo         = new UserRepository
  private lazy val userAuthRepo     = new UserAuthRepository
  private lazy val refreshTokenRepo = new RefreshTokenRepository
  private lazy val service          = new AuthAccountCleanupService(userAuthRepo, refreshTokenRepo)

  private def seed(user: User, token: VerifiedExternalToken): IO[Unit] =
    transactor.inTransaction {
      userRepo.create(user, now) *>
        userAuthRepo.save(UserAuth(TestData.randomUserAuthId(), user.id, token), now) *>
        refreshTokenRepo.save(TestData.refreshToken(userId = user.id)).as(())
    }

  "AuthAccountCleanupService.onAccountDeleted" should {
    "revoke provider identities and refresh tokens of the deleted user only — idempotently" in {
      val deleted      = TestData.user()
      val bystander    = TestData.user()
      val deletedToken = TestData.verifiedExternalToken()
      val otherToken   = TestData.verifiedExternalToken()
      for {
        _               <- seed(deleted, deletedToken)
        _               <- seed(bystander, otherToken)
        first           <- transactor.inTransaction(service.onAccountDeleted(UserAccountDeleted(deleted.id)))
        second          <- transactor.inTransaction(service.onAccountDeleted(UserAccountDeleted(deleted.id)))
        deletedAuth     <- transactor.inTransaction(userAuthRepo.findForUpdate(deletedToken.provider, deletedToken.providerUserId))
        bystanderAuth   <- transactor.inTransaction(userAuthRepo.findForUpdate(otherToken.provider, otherToken.providerUserId))
        deletedTokens   <- transactor.inSession(refreshTokenRepo.listActive(deleted.id, now))
        bystanderTokens <- transactor.inSession(refreshTokenRepo.listActive(bystander.id, now))
      } yield {
        first shouldBe Reaction.Done
        second shouldBe Reaction.Done
        deletedAuth shouldBe None
        bystanderAuth.map(_.userId) shouldBe Some(bystander.id)
        deletedTokens shouldBe empty
        bystanderTokens should have size 1
      }
    }
  }
}
