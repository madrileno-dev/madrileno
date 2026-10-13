package madrileno.auth.services

import cats.effect.std.{SecureRandom, UUIDGen}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import madrileno.auth.domain.*
import madrileno.auth.repositories.*
import madrileno.support.{FakeAuthVerifier, TestData, TestGivens, TestMailpit, TestTransactor}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.mailer.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.{Logger, LoggerFactory}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session
import skunk.codec.all.uuid
import skunk.implicits.*

import java.net.URI
import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class AuthenticationServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor with TestMailpit {

  private val testClock   = TestGivens.fixedClock()
  private val testUUIDGen = TestGivens.deterministicUUIDs()
  given Clock[IO]         = testClock
  given UUIDGen[IO]       = testUUIDGen
  given SecureRandom[IO]  = TestGivens.secureRandom
  given TelemetryContext  = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private val jwtConfig  = JwtService.Config(secret = "test-secret-at-least-256-bits-long-for-hs256!!", validFor = Duration.ofMinutes(5))
  private val jwtService = JwtService(jwtConfig)

  private lazy val userRepo         = new UserRepository
  private lazy val userAuthRepo     = new UserAuthRepository
  private lazy val refreshTokenRepo = new RefreshTokenRepository

  private lazy val smtpSender = SmtpSender(MailerConfig(host = mailpitHost, port = mailpitSmtpPort, fromAddress = "test@example.com", tls = false))
  private lazy val scheduler  = Scheduler(transactor, SchedulerConfig(pollingInterval = 1.second))
  private lazy val client     = scheduler.client
  private lazy val mailer     = new Mailer(smtpSender, client, MailContext(baseUrl = URI("https://example.com")))

  private def freshVerifiedToken() = TestData.verifiedExternalToken()

  private val reuseGrace   = Duration.ofSeconds(60)
  private val maxFamilyAge = Duration.ofDays(365)

  private def serviceWithFreshAuth(
    validFor: Duration = Duration.ofDays(90),
    reuseGrace: Duration = reuseGrace,
    maxFamilyAge: Duration = maxFamilyAge
  ) = {
    val token     = freshVerifiedToken()
    val verifiers = AuthVerifiers(Map(Provider.Firebase -> new FakeAuthVerifier(token)))
    val svc       = new AuthenticationService(
      userAuthRepo,
      refreshTokenRepo,
      userRepo,
      verifiers,
      jwtService,
      transactor,
      mailer,
      TestGivens.fingerprinter,
      AuthenticationService.Config(validFor, reuseGrace, maxFamilyAge)
    )
    (svc, token)
  }

  private val command = AuthenticateWithExternalTokenCommand(ExternalAuthToken("fake-token"), UserAgent("test-agent"), TestData.defaultIpAddress)

  "authenticateWithProvider" should {
    "create a new user on first login" in {
      val (service, _) = serviceWithFreshAuth()
      service.authenticateWithProvider(Provider.Firebase, command).map {
        case AuthenticationResult.UserCreated(jwt, issued) =>
          jwt.toString should not be empty
          issued.secret.toString should not be empty
          issued.token.secretHash shouldBe issued.secret.hash
        case other => fail(s"Expected UserCreated, got $other")
      }
    }

    "return ProviderUnavailable for an unknown provider" in {
      val (service, _) = serviceWithFreshAuth()
      service.authenticateWithProvider(Provider("nope"), command).map(_ shouldBe AuthenticationResult.ProviderUnavailable)
    }

    "return Authenticated on subsequent login" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        first  <- service.authenticateWithProvider(Provider.Firebase, command)
        second <- service.authenticateWithProvider(Provider.Firebase, command)
      } yield {
        first shouldBe a[AuthenticationResult.UserCreated]
        second shouldBe a[AuthenticationResult.Authenticated]
      }
    }

    "return InvalidToken for failed Firebase verification" in {
      val (service, _)        = serviceWithFreshAuth()
      val invalidTokenCommand = command.copy(token = ExternalAuthToken("rejected-external-token"))
      service.authenticateWithProvider(Provider.Firebase, invalidTokenCommand).map { result =>
        result shouldBe AuthenticationResult.InvalidToken
      }
    }

    "issue a JWT that can be decoded" in {
      val (service, token) = serviceWithFreshAuth()
      service.authenticateWithProvider(Provider.Firebase, command).map {
        case AuthenticationResult.UserCreated(jwt, _) =>
          jwtService.decode[AuthContext](jwt.toString) match {
            case DecodingResult.Decoded(ctx) =>
              ctx.fullName shouldBe token.profile.fullName
            case other => fail(s"Expected Decoded, got $other")
          }
        case other => fail(s"Expected UserCreated, got $other")
      }
    }

    "send welcome email on first login" in {
      val (service, _) = serviceWithFreshAuth()
      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _        <- clearMailpit()
            _        <- service.authenticateWithProvider(Provider.Firebase, command)
            messages <- waitForMail(_.exists(_.subject.contains("Welcome")))
          } yield messages
        }
        .map { messages =>
          messages.head.subject should include("Welcome")
        }
    }
  }

  private def issuedOf(result: AuthenticationResult): IssuedRefreshToken = result match {
    case AuthenticationResult.UserCreated(_, issued)   => issued
    case AuthenticationResult.Authenticated(_, issued) => issued
    case other                                         => fail(s"Expected tokens, got $other")
  }

  private def refreshWith(secret: RefreshTokenSecret) =
    AuthenticateWithRefreshTokenCommand(secret, UserAgent("test-agent"), TestData.defaultIpAddress)

  "authenticateWithRefreshToken" should {
    "authenticate with a valid refresh token and rotate within the same family" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        created <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(created)
        result <- service.authenticateWithRefreshToken(refreshWith(first.secret))
      } yield {
        result shouldBe a[AuthenticationResult.Authenticated]
        val second = issuedOf(result)
        second.token.familyId shouldBe first.token.familyId
        second.secret should not be first.secret
      }
    }

    "treat a row id or any other string that matches no stored secret as an unknown token" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        created <- service.authenticateWithProvider(Provider.Firebase, command)
        issued = issuedOf(created)
        garbage <- service.authenticateWithRefreshToken(refreshWith(RefreshTokenSecret("not-a-token")))
        rowId   <- service.authenticateWithRefreshToken(refreshWith(RefreshTokenSecret(issued.token.id.toString)))
        still   <- service.authenticateWithRefreshToken(refreshWith(issued.secret))
      } yield {
        garbage shouldBe AuthenticationResult.InvalidToken
        rowId shouldBe AuthenticationResult.InvalidToken
        still shouldBe a[AuthenticationResult.Authenticated]
      }
    }

    "revoke the family on an immediate replay when the reuse grace is zero" in {
      val (service, _) = serviceWithFreshAuth(reuseGrace = Duration.ZERO)
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
      } yield {
        replay shouldBe AuthenticationResult.InvalidToken
        afterward shouldBe AuthenticationResult.InvalidToken
      }
    }

    "revoke a family that has reached its maximum age even when the token itself is still valid" in {
      val (service, _) = serviceWithFreshAuth(validFor = Duration.ofDays(3650), maxFamilyAge = Duration.ofDays(1))
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        issued = issuedOf(login)
        _      = testClock.advance(Duration.ofDays(2).toMillis)
        result   <- service.authenticateWithRefreshToken(refreshWith(issued.secret))
        sessions <- service.listSessions(ListSessionsCommand(issued.token.userId))
      } yield {
        result shouldBe AuthenticationResult.InvalidToken
        sessions.map(_.familyId) should not contain issued.token.familyId
      }
    }

    "revoke a family that reaches its maximum age between a rotation and its in-window replay" in {
      val (service, _) = serviceWithFreshAuth(validFor = Duration.ofDays(3650), maxFamilyAge = Duration.ofDays(1))
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        _     = testClock.advance(Duration.ofDays(1).minus(reuseGrace.dividedBy(2)).toMillis)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        _ = testClock.advance(reuseGrace.dividedBy(2).plusSeconds(1).toMillis)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        successor <- service.authenticateWithRefreshToken(refreshWith(issuedOf(rotated).secret))
        sessions  <- service.listSessions(ListSessionsCommand(first.token.userId))
      } yield {
        rotated shouldBe a[AuthenticationResult.Authenticated]
        replay shouldBe AuthenticationResult.InvalidToken
        successor shouldBe AuthenticationResult.InvalidToken
        sessions.map(_.familyId) should not contain first.token.familyId
      }
    }

    "redeliver the successor when a replay lands within the reuse grace window" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        _      = testClock.advance(reuseGrace.minusSeconds(1).toMillis)
        replay <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        redelivered = issuedOf(replay)
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
      } yield {
        replay shouldBe a[AuthenticationResult.Authenticated]
        redelivered.secret shouldBe second.secret
        redelivered.token.id shouldBe second.token.id
        afterward shouldBe a[AuthenticationResult.Authenticated]
      }
    }

    "redeliver the same successor to every retry within the window" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        retry1 <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        retry2 <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        moved  <- service.authenticateWithRefreshToken(refreshWith(second.secret))
        again  <- service.authenticateWithRefreshToken(refreshWith(second.secret))
      } yield {
        issuedOf(retry1).secret shouldBe second.secret
        issuedOf(retry2).secret shouldBe second.secret
        moved shouldBe a[AuthenticationResult.Authenticated]
        issuedOf(again).secret shouldBe issuedOf(moved).secret
      }
    }

    "treat a replay within the grace window as theft once the successor was used" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        moved <- service.authenticateWithRefreshToken(refreshWith(second.secret))
        third = issuedOf(moved)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(third.secret))
      } yield {
        replay shouldBe AuthenticationResult.InvalidToken
        afterward shouldBe AuthenticationResult.InvalidToken
      }
    }

    "reject a replay after the grace window and revoke its whole family" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        _      = testClock.advance(reuseGrace.plusSeconds(1).toMillis)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
      } yield {
        replay shouldBe AuthenticationResult.InvalidToken
        afterward shouldBe AuthenticationResult.InvalidToken
      }
    }

    "leave other families untouched when one family is revoked by replay" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        loginA <- service.authenticateWithProvider(Provider.Firebase, command)
        loginB <- service.authenticateWithProvider(Provider.Firebase, command)
        issuedA = issuedOf(loginA)
        issuedB = issuedOf(loginB)
        _ <- service.authenticateWithRefreshToken(refreshWith(issuedA.secret))
        _ = testClock.advance(reuseGrace.plusSeconds(1).toMillis)
        _       <- service.authenticateWithRefreshToken(refreshWith(issuedA.secret))
        bResult <- service.authenticateWithRefreshToken(refreshWith(issuedB.secret))
      } yield bResult shouldBe a[AuthenticationResult.Authenticated]
    }

    "revoke the family when a replay's successor row no longer exists" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        _         <- deleteTokenRow(second.token.id)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        sessions  <- service.listSessions(ListSessionsCommand(first.token.userId))
      } yield {
        replay shouldBe AuthenticationResult.InvalidToken
        afterward shouldBe AuthenticationResult.InvalidToken
        sessions.map(_.familyId) should not contain first.token.familyId
      }
    }

    "revoke the family when a replay's successor was revoked in the meantime" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        _         <- revokeTokenRow(second.token.id)
        replay    <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
      } yield {
        replay shouldBe AuthenticationResult.InvalidToken
        afterward shouldBe AuthenticationResult.InvalidToken
      }
    }

    "reject an unknown refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      service
        .authenticateWithRefreshToken(refreshWith(TestData.refreshTokenSecret()))
        .map(_ shouldBe AuthenticationResult.InvalidToken)
    }

    "reject an expired refresh token" in {
      val (service, _) = serviceWithFreshAuth(validFor = Duration.ofMinutes(5))
      for {
        created <- service.authenticateWithProvider(Provider.Firebase, command)
        issued = issuedOf(created)
        _      = testClock.advance(Duration.ofMinutes(10).toMillis)
        result <- service.authenticateWithRefreshToken(refreshWith(issued.secret))
      } yield result shouldBe AuthenticationResult.InvalidToken
    }
  }

  "logout" should {
    "revoke the whole family behind the presented refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        _         <- service.logout(LogoutCommand(first.secret))
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
        sessions  <- service.listSessions(ListSessionsCommand(second.token.userId))
      } yield {
        afterward shouldBe AuthenticationResult.InvalidToken
        sessions.map(_.familyId) should not contain second.token.familyId
      }
    }

    "leave other sessions of the user alone" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        loginA <- service.authenticateWithProvider(Provider.Firebase, command)
        loginB <- service.authenticateWithProvider(Provider.Firebase, command)
        issuedA = issuedOf(loginA)
        issuedB = issuedOf(loginB)
        _      <- service.logout(LogoutCommand(issuedA.secret))
        bAfter <- service.authenticateWithRefreshToken(refreshWith(issuedB.secret))
      } yield bAfter shouldBe a[AuthenticationResult.Authenticated]
    }

    "succeed for an unknown or already revoked refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        issued = issuedOf(login)
        _ <- service.logout(LogoutCommand(issued.secret))
        _ <- service.logout(LogoutCommand(issued.secret))
        _ <- service.logout(LogoutCommand(TestData.refreshTokenSecret()))
      } yield succeed
    }
  }

  "sessions" should {
    "list one live entry per family and keep the family id stable across rotations" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        sessions <- service.listSessions(ListSessionsCommand(first.token.userId))
      } yield {
        sessions.map(_.familyId) shouldBe List(first.token.familyId)
        sessions.map(_.id) shouldBe List(second.token.id)
      }
    }

    "revokeSession revokes the whole family so its live successor stops working" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        first = issuedOf(login)
        rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
        second = issuedOf(rotated)
        revoked   <- service.revokeSession(RevokeSessionCommand(first.token.userId, first.token.familyId))
        afterward <- service.authenticateWithRefreshToken(refreshWith(second.secret))
        sessions  <- service.listSessions(ListSessionsCommand(first.token.userId))
      } yield {
        revoked shouldBe true
        afterward shouldBe AuthenticationResult.InvalidToken
        sessions shouldBe empty
      }
    }

    "revokeSession refuses a family owned by another user" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        login <- service.authenticateWithProvider(Provider.Firebase, command)
        issued = issuedOf(login)
        revoked   <- service.revokeSession(RevokeSessionCommand(TestData.randomUserId(), issued.token.familyId))
        afterward <- service.authenticateWithRefreshToken(refreshWith(issued.secret))
      } yield {
        revoked shouldBe false
        afterward shouldBe a[AuthenticationResult.Authenticated]
      }
    }

    "revokeSessionsByUserAgent revokes every family of that user agent and leaves the others" in {
      val (service, _) = serviceWithFreshAuth()
      val firefox      = command.copy(userAgent = UserAgent("Firefox"))
      val chrome       = command.copy(userAgent = UserAgent("Chrome"))
      for {
        loginA <- service.authenticateWithProvider(Provider.Firebase, firefox)
        loginB <- service.authenticateWithProvider(Provider.Firebase, firefox)
        loginC <- service.authenticateWithProvider(Provider.Firebase, chrome)
        issuedA = issuedOf(loginA)
        issuedB = issuedOf(loginB)
        issuedC = issuedOf(loginC)
        count  <- service.revokeSessionsByUserAgent(RevokeSessionsByUserAgentCommand(issuedA.token.userId, UserAgent("Firefox")))
        aAfter <- service.authenticateWithRefreshToken(refreshWith(issuedA.secret))
        bAfter <- service.authenticateWithRefreshToken(refreshWith(issuedB.secret))
        cAfter <- service.authenticateWithRefreshToken(refreshWith(issuedC.secret))
      } yield {
        count shouldBe 2
        aAfter shouldBe AuthenticationResult.InvalidToken
        bAfter shouldBe AuthenticationResult.InvalidToken
        cAfter shouldBe a[AuthenticationResult.Authenticated]
      }
    }
  }

  "logging" should {
    "carry fingerprints of credentials, never the credentials themselves" in {
      val (service, _) = serviceWithFreshAuth(reuseGrace = Duration.ZERO)
      capturingLogs {
        for {
          login <- service.authenticateWithProvider(Provider.Firebase, command)
          first = issuedOf(login)
          rotated <- service.authenticateWithRefreshToken(refreshWith(first.secret))
          second = issuedOf(rotated)
          _ <- service.authenticateWithRefreshToken(refreshWith(first.secret))
          _ <- service.authenticateWithRefreshToken(refreshWith(RefreshTokenSecret("garbage-token")))
          _ <- service.authenticateWithProvider(Provider.Firebase, command.copy(token = ExternalAuthToken("rejected-external-token")))
        } yield (
          List(first.secret.unwrap, second.secret.unwrap, jwtOf(login), jwtOf(rotated), "garbage-token", "rejected-external-token"),
          first.secret.unwrap
        )
      }.map { case ((secrets, replayed), logged) =>
        logged should not be empty
        secrets.foreach { secret =>
          withClue(s"log lines containing a credential:\n${logged.filter(_.contains(secret)).mkString("\n")}\n") {
            logged.exists(_.contains(secret)) shouldBe false
          }
        }
        logged.count(_.contains(TestGivens.fingerprinter(replayed).value)) should be >= 1
        logged.count(_.contains(TestGivens.fingerprinter(RefreshTokenSecret(replayed)).value)) should be >= 1
        logged.exists(_.contains(TestGivens.fingerprinter("garbage-token").value)) shouldBe true
        logged.exists(_.contains(TestGivens.fingerprinter("rejected-external-token").value)) shouldBe true
      }
    }
  }

  private def deleteTokenRow(id: RefreshTokenId): IO[Unit] = withSession {
    summon[Session[IO]].execute(sql"DELETE FROM refresh_token WHERE id = $uuid".command)(id.unwrap).void
  }

  private def revokeTokenRow(id: RefreshTokenId): IO[Unit] = withSession {
    summon[Session[IO]].execute(sql"UPDATE refresh_token SET deleted_at = now() WHERE id = $uuid".command)(id.unwrap).void
  }

  private def jwtOf(result: AuthenticationResult): String = result match {
    case AuthenticationResult.UserCreated(jwt, _)   => jwt.unwrap
    case AuthenticationResult.Authenticated(jwt, _) => jwt.unwrap
    case other                                      => fail(s"Expected tokens, got $other")
  }

  private def capturingLogs[A](io: IO[A]): IO[(A, List[String])] = {
    val attach = IO {
      LoggerFactory.getILoggerFactory match {
        case context: LoggerContext =>
          val root     = context.getLogger(Logger.ROOT_LOGGER_NAME)
          val appender = new ListAppender[ILoggingEvent]
          appender.start()
          root.addAppender(appender)
          (root, appender)
        case other => fail(s"expected logback, found ${other.getClass.getName}")
      }
    }
    attach.bracket { case (_, appender) =>
      io.map { result =>
        val lines = appender.list.asScala.toList.map { event =>
          val cause = Option(event.getThrowableProxy).map(proxy => s" ${proxy.getClassName}: ${proxy.getMessage}").getOrElse("")
          s"${event.getFormattedMessage}$cause"
        }
        (result, lines)
      }
    } { case (root, appender) => IO { root.detachAppender(appender); appender.stop() } }
  }
}
