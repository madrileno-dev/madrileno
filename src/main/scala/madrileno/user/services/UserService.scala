package madrileno.user.services

import cats.effect.{Clock, IO}
import madrileno.user.domain.{User, UserAccountDeleted, UserId}
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.outbox.Outbox
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import pl.iterators.sealedmonad.syntax.*

class UserService(
  userRepository: UserRepository,
  outbox: Outbox,
  transactor: Transactor
)(using
  TelemetryContext,
  Clock[IO])
    extends LoggingSupport {
  def getCurrentUser(userId: UserId): IO[Option[User]] =
    transactor.inSession(userRepository.find(userId))

  def deleteAccount(userId: UserId): IO[DeleteAccountResult] =
    transactor
      .inTransaction {
        (for {
          now <- Clock[IO].realTimeInstant.seal
          _   <- userRepository.anonymize(userId, now).seal.ensure(identity, DeleteAccountResult.AlreadyDeleted)
          _   <- outbox.publishTransactionally(UserAccountDeleted(userId)).seal
        } yield DeleteAccountResult.Deleted).run
      }
      .flatTap {
        case DeleteAccountResult.Deleted        => logger.info(s"account deleted: $userId")
        case DeleteAccountResult.AlreadyDeleted => IO.unit
      }
}

enum DeleteAccountResult {
  case Deleted, AlreadyDeleted
}
