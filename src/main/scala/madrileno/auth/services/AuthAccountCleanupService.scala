package madrileno.auth.services

import cats.effect.{Clock, IO}
import madrileno.auth.repositories.{RefreshTokenRepository, UserAuthRepository}
import madrileno.user.domain.{UserAccountDeleted, UserId}
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.events.outbox.Reaction
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import java.time.Instant

class AuthAccountCleanupService(
  userAuthRepository: UserAuthRepository,
  refreshTokenRepository: RefreshTokenRepository
)(using
  TelemetryContext,
  Clock[IO])
    extends LoggingSupport {

  def onAccountDeleted(event: UserAccountDeleted): DBInTransaction[Reaction] =
    for {
      now <- Clock[IO].realTimeInstant
      _   <- cleanUp(event.userId, now)
      _   <- logger.info(s"ran auth cleanup after account deletion of ${event.userId}")
    } yield Reaction.Done

  def cleanUp(userId: UserId, now: Instant): DB[Unit] =
    userAuthRepository.softDeleteByUser(userId, now) *> refreshTokenRepository.revokeAllForUser(userId, now)
}
