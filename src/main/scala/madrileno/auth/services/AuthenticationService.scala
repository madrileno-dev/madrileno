package madrileno.auth.services

import cats.effect.std.{SecureRandom, UUIDGen}
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.auth.emails.WelcomeEmailTemplate
import madrileno.auth.repositories.*
import madrileno.user.domain.*
import madrileno.user.repositories.*
import madrileno.utils.crypto.{IdGenerator, RandomSecret}
import madrileno.utils.db.transactor.*
import madrileno.utils.mailer.{Language, Mailer}
import madrileno.utils.observability.{Fingerprint, Fingerprinter, LoggingSupport, TelemetryContext}
import madrileno.utils.task.{CronExpression, Schedule, Task}
import pl.iterators.sealedmonad.syntax.*
import pureconfig.*

import java.time.{Duration, Instant}

class AuthenticationService(
  userAuthRepository: UserAuthRepository,
  refreshTokenRepository: RefreshTokenRepository,
  userRepository: UserRepository,
  accountCleanup: AuthAccountCleanupService,
  verifiers: AuthVerifiers,
  jwtService: JwtService,
  transactor: Transactor,
  mailer: Mailer,
  fingerprinter: Fingerprinter,
  config: AuthenticationService.Config
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO],
  SecureRandom[IO])
    extends LoggingSupport {
  def authenticateWithProvider(provider: Provider, command: AuthenticateWithExternalTokenCommand): IO[AuthenticationResult] = {
    verifiers.get(provider) match {
      case None =>
        logger.warn(s"No auth verifier configured for provider $provider").as(AuthenticationResult.ProviderUnavailable)
      case Some(verifier) =>
        verifier.verifyToken(command.token).flatMap {
          case Left(t) =>
            logger
              .error(
                s"Failed to authenticate with $provider (token ${fingerprinter(command.token)}): ${t.getMessage} ${t.getStackTrace.toList.mkString("\n")}"
              )
              .as(AuthenticationResult.InvalidToken)
          case Right(verifiedToken) if verifiedToken.provider != provider =>
            logger
              .error(s"Verifier for $provider returned a token claiming provider ${verifiedToken.provider}; refusing")
              .as(AuthenticationResult.InvalidToken)
          case Right(verifiedToken) =>
            transactor.inTransaction(upsertAndIssueTokens(verifiedToken, command))
        }
    }
  }

  private def upsertAndIssueTokens(verifiedToken: VerifiedExternalToken, command: AuthenticateWithExternalTokenCommand)
    : DBInTransaction[AuthenticationResult] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      userAuthRepository.findForUpdate(verifiedToken.provider, verifiedToken.providerUserId).flatMap {
        case Some(userAuth) => updateExistingUser(userAuth, verifiedToken, command, now)
        case None           => createNewUser(verifiedToken, command, now)
      }
    }
  }

  private def registerAfterDeletedAccount(
    userAuth: UserAuth,
    verifiedToken: VerifiedExternalToken,
    command: AuthenticateWithExternalTokenCommand,
    now: Instant
  ): DBInTransaction[AuthenticationResult] =
    logger.info(s"Login via ${userAuth.provider} matched deleted user ${userAuth.userId}; cleaning up before registering a new user") *>
      accountCleanup.cleanUp(userAuth.userId, now) *>
      createNewUser(verifiedToken, command, now)

  private def updateExistingUser(
    userAuth: UserAuth,
    verifiedToken: VerifiedExternalToken,
    command: AuthenticateWithExternalTokenCommand,
    now: Instant
  ): DBInTransaction[AuthenticationResult] = {
    val userUpdater: User => User = _.withUpdatedProfile(verifiedToken.profile)
    userRepository.update(userAuth.userId, userUpdater, now).flatMap {
      case true =>
        userAuthRepository.updateMetadata(userAuth.id, verifiedToken.metadata) *>
          generateTokensInNewFamily(userAuth.userId, command.userAgent, command.ipAddress, now, AuthenticationResult.Authenticated.apply)
      case false => registerAfterDeletedAccount(userAuth, verifiedToken, command, now)
    }
  }

  private def createNewUser(
    verifiedToken: VerifiedExternalToken,
    command: AuthenticateWithExternalTokenCommand,
    now: Instant
  ): DBInTransaction[AuthenticationResult] = {
    for {
      user     <- IdGenerator.generateId(UserId).map(verifiedToken.toUser)
      userAuth <- IdGenerator.generateId(UserAuthId).map(id => UserAuth(id, user.id, verifiedToken))
      _        <- userRepository.create(user, now)
      _        <- userAuthRepository.save(userAuth, now)
      _        <- logger.info(s"Created new user: $user via ${verifiedToken.provider} (${verifiedToken.providerUserId})")
      _        <- user.emailAddress.fold(IO.unit) { email =>
             mailer.sendTransactionally(to = List(email.toString), template = WelcomeEmailTemplate(user.fullName), lang = Language.En).void
           }
      tokens <- generateTokensInNewFamily(user.id, command.userAgent, command.ipAddress, now, AuthenticationResult.UserCreated.apply)
    } yield tokens
  }

  def authenticateWithRefreshToken(command: AuthenticateWithRefreshTokenCommand): IO[AuthenticationResult] = {
    val client    = s"${command.ipAddress} (${command.userAgent})"
    val secret    = command.refreshToken
    val presented = fingerprinter(secret)
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        refreshTokenRepository
          .findAndLockFamilyBySecretHash(secret.hash)
          .flatMap {
            case Some(refreshToken)
                if (refreshToken.isValid(now) || refreshToken.canRedeliverWithin(config.reuseGrace, now)) &&
                  refreshToken.family.olderThan(config.maxFamilyAge, now) =>
              refreshTokenRepository.revokeFamily(refreshToken.familyId, now) *>
                logger
                  .info(s"Refresh token family ${refreshToken.familyId} for user ${refreshToken.userId} reached its maximum age; revoked")
                  .as(AuthenticationResult.InvalidToken)
            case Some(refreshToken) if refreshToken.isValid(now) =>
              generateTokens(
                refreshToken.userId,
                refreshToken.family,
                command.userAgent,
                command.ipAddress,
                now,
                AuthenticationResult.Authenticated.apply
              ).flatMap { result =>
                val consumed = result match {
                  case AuthenticationResult.Authenticated(_, issued) => secret.sealSuccessor(issued.secret).map(refreshToken.rotatedTo(_, now))
                  case _                                             => IO.pure(refreshToken.usedAt(now))
                }
                consumed.flatMap(refreshTokenRepository.update).as(result)
              }
            case Some(refreshToken) if refreshToken.canRedeliverWithin(config.reuseGrace, now) =>
              redeliverSuccessor(refreshToken, secret, client, presented, now)
            case Some(refreshToken) if refreshToken.isUsed && !refreshToken.isRevoked =>
              refreshTokenRepository.revokeFamily(refreshToken.familyId, now) *>
                logger
                  .warn(
                    s"Refresh token ${refreshToken.id} ($presented) was replayed from $client; revoked family ${refreshToken.familyId} for user ${refreshToken.userId}"
                  )
                  .as(AuthenticationResult.InvalidToken)
            case Some(refreshToken) =>
              logger
                .warn(s"Refresh token ${refreshToken.id} ($presented) presented from $client is revoked or expired")
                .as(AuthenticationResult.InvalidToken)
            case None =>
              logger.warn(s"Unknown refresh token $presented presented from $client").as(AuthenticationResult.InvalidToken)
          }
      }
    }
  }

  def logout(command: LogoutCommand): IO[Unit] = {
    val presented = fingerprinter(command.refreshToken)
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        (for {
          refreshToken <-
            refreshTokenRepository
              .findAndLockFamilyBySecretHash(command.refreshToken.hash)
              .valueOrF[Unit](logger.info(s"Logout with unknown refresh token $presented"))
              .ensureNotOrF(
                _.isRevoked,
                token => logger.debug(s"Logout with already revoked refresh token ${token.id} ($presented); family ${token.familyId} untouched")
              )
          _ <- refreshTokenRepository.revokeFamily(refreshToken.familyId, now).seal
          _ <-
            logger
              .info(
                s"Logout with refresh token ${refreshToken.id} ($presented): revoked family ${refreshToken.familyId} for user ${refreshToken.userId}"
              )
              .seal
        } yield ()).run
      }
    }
  }

  def listSessions(command: ListSessionsCommand): IO[List[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inSession {
        refreshTokenRepository.listActive(command.userId, now)
      }
    }
  }

  def revokeSession(command: RevokeSessionCommand): IO[Boolean] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction {
        refreshTokenRepository.lockFamily(command.familyId) *>
          refreshTokenRepository.listActiveByFamily(command.familyId, now).flatMap {
            case Nil =>
              logger.warn(s"Session ${command.familyId} for user ${command.userId} not found or already inactive").as(false)
            case tokens if tokens.exists(_.userId != command.userId) =>
              logger.warn(s"Attempt to revoke session ${command.familyId} by user ${command.userId} who does not own it").as(false)
            case _ =>
              refreshTokenRepository.revokeFamily(command.familyId, now) *>
                logger.info(s"Revoked session ${command.familyId} for user ${command.userId}").as(true)
          }
      }
    }
  }

  def revokeSessionsByUserAgent(command: RevokeSessionsByUserAgentCommand): IO[Int] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction {
        refreshTokenRepository
          .listActiveByUserAgent(command.userId, command.userAgent, now)
          .map(_.map(_.familyId).distinct)
          .flatMap { families =>
            refreshTokenRepository.lockFamilies(families) *>
              families.traverse_(refreshTokenRepository.revokeFamily(_, now)) *>
              logger.info(s"Revoked ${families.size} sessions for user ${command.userId} and user agent ${command.userAgent}").as(families.size)
          }
      }
    }
  }

  val cleanupExpiredRefreshTokensTask: Task[Unit] =
    Task.recurring("cleanup-expired-refresh-tokens", Schedule.Cron(CronExpression.unsafeParse("0 0 1 ? * 0-6"))) { _ =>
      Clock[IO].realTimeInstant.flatMap { now =>
        val cutoff = now.minus(Duration.ofDays(60))
        transactor.inSession {
          refreshTokenRepository.deleteStaleBefore(cutoff)
        }
      }
    }

  private def redeliverSuccessor(
    used: RefreshToken,
    presented: RefreshTokenSecret,
    client: String,
    fingerprint: Fingerprint,
    now: Instant
  ): DB[AuthenticationResult] = {
    def revokeAsReplay(reason: String): DB[AuthenticationResult] =
      refreshTokenRepository.revokeFamily(used.familyId, now) *>
        logger
          .warn(
            s"Refresh token ${used.id} ($fingerprint) was replayed within the reuse grace window from $client but $reason; revoked family ${used.familyId} for user ${used.userId}"
          )
          .as(AuthenticationResult.InvalidToken)

    (for {
      successorSecret <-
        IO.pure(used.successor.flatMap(presented.openSuccessor)).valueOrF[AuthenticationResult](revokeAsReplay("carries no successor to redeliver"))
      successor <- refreshTokenRepository
                     .findBySecretHash(successorSecret.hash)
                     .valueOrF(revokeAsReplay("its successor is gone"))
                     .ensureF(_.isValid(now), revokeAsReplay("its successor was already used"))
      user <- userRepository
                .find(successor.userId)
                .valueOrF[AuthenticationResult](
                  logger
                    .warn(s"Refusing to redeliver refresh token ${successor.id} for deleted user ${successor.userId}")
                    .as(AuthenticationResult.InvalidToken)
                )
                .ensure(_.isActive, AuthenticationResult.UserBlocked)
      _ <-
        logger
          .info(
            s"Refresh token ${used.id} ($fingerprint) was replayed within the reuse grace window from $client; redelivered its successor ${successor.id}"
          )
          .seal
    } yield AuthenticationResult.Authenticated(jwtService.encode(AuthContext(user), now), IssuedRefreshToken(successor, successorSecret))).run
  }

  private def generateTokensInNewFamily(
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    now: Instant,
    success: (InternalJwt, IssuedRefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    IdGenerator.generateId(RefreshTokenFamilyId).flatMap { familyId =>
      generateTokens(userId, RefreshTokenFamily(familyId, now), userAgent, ipAddress, now, success)
    }
  }

  private def generateTokens(
    userId: UserId,
    family: RefreshTokenFamily,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    now: Instant,
    success: (InternalJwt, IssuedRefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    (for {
      user <-
        userRepository
          .find(userId)
          .valueOrF[AuthenticationResult](logger.warn(s"Refusing to issue tokens for deleted user $userId").as(AuthenticationResult.InvalidToken))
          .ensure(_.isActive, AuthenticationResult.UserBlocked)
      jwt = jwtService.encode(AuthContext(user), now)
      id     <- IdGenerator.generateId(RefreshTokenId).seal
      secret <- RandomSecret.generate(RefreshTokenSecret.byteLength).map(RefreshTokenSecret.apply).seal
      refreshToken = RefreshToken.mint(id, family, secret.hash, now, user.id, userAgent, ipAddress, config.validFor)
      _ <- refreshTokenRepository.save(refreshToken).seal
      _ <-
        logger
          .debug(
            s"Issued JWT ${fingerprinter(jwt)} and refresh token ${refreshToken.id} (${fingerprinter(secret)}) in family ${family.id} for user: $userId"
          )
          .seal
    } yield {
      success(jwt, IssuedRefreshToken(refreshToken, secret))
    }).run
  }
}

final case class AuthenticateWithExternalTokenCommand(
  token: ExternalAuthToken,
  userAgent: UserAgent,
  ipAddress: IpAddress)

final case class AuthenticateWithRefreshTokenCommand(
  refreshToken: RefreshTokenSecret,
  userAgent: UserAgent,
  ipAddress: IpAddress)

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt, refreshToken: IssuedRefreshToken)
  case UserCreated(jwt: InternalJwt, refreshToken: IssuedRefreshToken)
  case UserBlocked
  case InvalidToken
  case ProviderUnavailable
}

final case class LogoutCommand(refreshToken: RefreshTokenSecret)

final case class ListSessionsCommand(userId: UserId)

final case class RevokeSessionCommand(userId: UserId, familyId: RefreshTokenFamilyId)

final case class RevokeSessionsByUserAgentCommand(userId: UserId, userAgent: UserAgent)

object AuthenticationService {
  final case class Config(
    validFor: Duration,
    reuseGrace: Duration,
    maxFamilyAge: Duration)
      derives ConfigReader
}
