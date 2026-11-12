package madrileno.auth.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO}
import madrileno.auth.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.domain.UserId
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.DBInTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*

class RefreshTokenRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val userRepo  = new UserRepository
  private lazy val tokenRepo = new RefreshTokenRepository

  private def createUserAndToken(usedAt: Option[Instant] = None, deletedAt: Option[Instant] = None): DBInTransaction[(UserId, RefreshToken)] = {
    val user  = TestData.user()
    val token = TestData.refreshToken(userId = user.id, usedAt = usedAt, deletedAt = deletedAt)
    for {
      _     <- userRepo.create(user, Instant.now())
      saved <- tokenRepo.save(token)
    } yield (user.id, saved)
  }

  private def advisoryLockWaiters: IO[Int] =
    transactor.inSession {
      summon[Session[IO]].unique(
        sql"SELECT count(*)::int4 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = 'advisory' AND datname = current_database()"
          .query(int4)
      )
    }

  private def awaitAdvisoryLockWaiter(attemptsLeft: Int = 250): IO[Unit] =
    advisoryLockWaiters.flatMap {
      case n if n > 0            => IO.unit
      case _ if attemptsLeft > 0 => IO.sleep(20.millis) *> awaitAdvisoryLockWaiter(attemptsLeft - 1)
      case _                     => IO.raiseError(new AssertionError("no session ever blocked on the family advisory lock"))
    }

  "RefreshTokenRepository" should {
    "save and list active tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield {
        active.size shouldBe 1
        active.head.id shouldBe token.id
      }
    }

    "listActive excludes used tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.usedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield active shouldBe empty
    }

    "listActive excludes soft-deleted tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.deletedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield active shouldBe empty
    }

    "listActive excludes expired tokens" in withRollback {
      val user    = TestData.user()
      val expired = TestData.refreshToken(userId = user.id, expiresAt = Instant.now().minusSeconds(1))
      for {
        _      <- userRepo.create(user, Instant.now())
        _      <- tokenRepo.save(expired)
        active <- tokenRepo.listActive(user.id, Instant.now())
      } yield active shouldBe empty
    }

    "listActive returns empty for unknown user" in withRollback {
      tokenRepo.listActive(TestData.randomUserId(), Instant.now()).map(_ shouldBe empty)
    }

    "listActiveByUserAgent returns only live tokens with that user agent" in withRollback {
      val now     = Instant.now()
      val user    = TestData.user()
      val firefox = TestData.refreshToken(userId = user.id, userAgent = UserAgent("Firefox"))
      val chrome  = TestData.refreshToken(userId = user.id, userAgent = UserAgent("Chrome"))
      val usedFf  = TestData.refreshToken(userId = user.id, userAgent = UserAgent("Firefox"), usedAt = Some(now))
      for {
        _     <- userRepo.create(user, now)
        _     <- tokenRepo.save(firefox) *> tokenRepo.save(chrome) *> tokenRepo.save(usedFf)
        found <- tokenRepo.listActiveByUserAgent(user.id, UserAgent("Firefox"), now)
      } yield found.map(_.id) shouldBe List(firefox.id)
    }

    "listActiveByFamily returns only live tokens of that family" in withRollback {
      val now      = Instant.now()
      val user     = TestData.user()
      val familyId = TestData.randomRefreshTokenFamilyId()
      val used     = TestData.refreshToken(userId = user.id, familyId = familyId, usedAt = Some(now))
      val live     = TestData.refreshToken(userId = user.id, familyId = familyId)
      val other    = TestData.refreshToken(userId = user.id)
      for {
        _     <- userRepo.create(user, now)
        _     <- tokenRepo.save(used) *> tokenRepo.save(live) *> tokenRepo.save(other)
        found <- tokenRepo.listActiveByFamily(familyId, now)
      } yield found.map(_.id) shouldBe List(live.id)
    }

    "find returns the token or None" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        found      <- tokenRepo.find(token.id)
        missing    <- tokenRepo.find(TestData.randomRefreshTokenId())
      } yield {
        found.map(_.id) shouldBe Some(token.id)
        missing shouldBe None
      }
    }

    "findBySecretHash returns the token owning that hash or None" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        found      <- tokenRepo.findBySecretHash(token.secretHash)
        missing    <- tokenRepo.findBySecretHash(TestData.refreshTokenSecret().hash)
      } yield {
        found.map(_.id) shouldBe Some(token.id)
        missing shouldBe None
      }
    }

    "update marks token as used" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        _          <- tokenRepo.update(token.usedAt(Instant.now()))
        found      <- tokenRepo.find(token.id)
      } yield found.flatMap(_.usedAt) shouldBe defined
    }

    "findAndLockFamilyBySecretHash serializes a replay's revocation behind an in-flight rotation" in {
      val now      = Instant.now()
      val user     = TestData.user()
      val familyId = TestData.randomRefreshTokenFamilyId()
      val replayed = TestData.issuedRefreshToken(userId = user.id, familyId = familyId, usedAt = Some(now.minusSeconds(3600)))
      val live     = TestData.issuedRefreshToken(userId = user.id, familyId = familyId)
      val next     = TestData.refreshToken(userId = user.id, familyId = familyId)
      for {
        _                 <- transactor.inTransaction(userRepo.create(user, now) *> tokenRepo.save(replayed.token) *> tokenRepo.save(live.token))
        rotationHoldsLock <- Deferred[IO, Unit]
        releaseRotation   <- Deferred[IO, Unit]
        rotation          <- transactor.inTransaction {
                      tokenRepo.findAndLockFamilyBySecretHash(live.token.secretHash).flatMap { locked =>
                        tokenRepo.update(live.token.usedAt(now)) *>
                          rotationHoldsLock.complete(()) *>
                          releaseRotation.get *>
                          tokenRepo.save(next).as(locked.map(_.id))
                      }
                    }.start
        _      <- rotationHoldsLock.get
        replay <- transactor.inTransaction {
                    tokenRepo.findAndLockFamilyBySecretHash(replayed.token.secretHash).flatMap { _ =>
                      tokenRepo.revokeFamily(familyId, now)
                    }
                  }.start
        _         <- awaitAdvisoryLockWaiter()
        _         <- releaseRotation.complete(())
        lockedId  <- rotation.joinWithNever
        _         <- replay.joinWithNever
        activeNow <- transactor.inSession(tokenRepo.listActive(user.id, now))
        nextAfter <- transactor.inSession(tokenRepo.findBySecretHash(next.secretHash))
      } yield {
        lockedId shouldBe Some(live.token.id)
        activeNow shouldBe empty
        nextAfter.flatMap(_.deletedAt) shouldBe defined
      }
    }

    "lockFamilies is a no-op for an empty list and locks distinct families once" in withRollback {
      tokenRepo.lockFamilies(Nil) *>
        tokenRepo.lockFamilies(List(TestData.randomRefreshTokenFamilyId(), TestData.randomRefreshTokenFamilyId())).map(_ => succeed)
    }

    "revokeFamily invalidates every token in the family and nothing else" in withRollback {
      val now      = Instant.now()
      val user     = TestData.user()
      val familyId = TestData.randomRefreshTokenFamilyId()
      val used     = TestData.refreshToken(userId = user.id, familyId = familyId, usedAt = Some(now))
      val live     = TestData.refreshToken(userId = user.id, familyId = familyId)
      val other    = TestData.refreshToken(userId = user.id)
      for {
        _         <- userRepo.create(user, now)
        _         <- tokenRepo.save(used) *> tokenRepo.save(live) *> tokenRepo.save(other)
        _         <- tokenRepo.revokeFamily(familyId, now)
        active    <- tokenRepo.listActive(user.id, now)
        liveAfter <- tokenRepo.find(live.id)
        usedAfter <- tokenRepo.find(used.id)
      } yield {
        active.map(_.id) shouldBe List(other.id)
        liveAfter.flatMap(_.deletedAt) shouldBe defined
        usedAfter.flatMap(_.deletedAt) shouldBe defined
      }
    }

    "revokeAllForUser invalidates active refresh tokens" in withRollback {
      val now = Instant.now()
      for {
        (userId, _) <- createUserAndToken()
        before      <- tokenRepo.listActive(userId, now)
        _ = before should not be empty
        _     <- tokenRepo.revokeAllForUser(userId, now)
        after <- tokenRepo.listActive(userId, now)
      } yield after shouldBe empty
    }
  }

  "deleteStaleBefore" should {
    "keep used tokens as replay evidence until their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (_, token) <- createUserAndToken(usedAt = Some(old))
        _          <- tokenRepo.deleteStaleBefore(cutoff)
        found      <- tokenRepo.find(token.id)
      } yield found shouldBe defined
    }

    "delete used tokens once their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      val user   = TestData.user()
      val token  = TestData.refreshToken(userId = user.id, usedAt = Some(old.minusSeconds(60)), expiresAt = old)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.find(token.id)
      } yield found shouldBe None
    }

    "keep revoked tokens until their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (_, token) <- createUserAndToken(deletedAt = Some(old))
        _          <- tokenRepo.deleteStaleBefore(cutoff)
        found      <- tokenRepo.find(token.id)
      } yield found shouldBe defined
    }

    "NOT delete active tokens" in withRollback {
      val cutoff = Instant.now()
      for {
        (userId, _) <- createUserAndToken()
        _           <- tokenRepo.deleteStaleBefore(cutoff)
        active      <- tokenRepo.listActive(userId, Instant.now())
      } yield active.size shouldBe 1
    }

    "delete never-used tokens expired before cutoff" in withRollback {
      val cutoff       = Instant.now()
      val oldExpiresAt = cutoff.minus(1, ChronoUnit.DAYS)
      val user         = TestData.user()
      val token        = TestData.refreshToken(userId = user.id, expiresAt = oldExpiresAt)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.find(token.id)
      } yield found shouldBe None
    }

    "NOT delete tokens that expire after cutoff" in withRollback {
      val cutoff          = Instant.now()
      val futureExpiresAt = cutoff.plus(1, ChronoUnit.DAYS)
      val user            = TestData.user()
      val token           = TestData.refreshToken(userId = user.id, expiresAt = futureExpiresAt)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.find(token.id)
      } yield found shouldBe defined
    }
  }
}
