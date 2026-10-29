package madrileno.user.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.user.repositories.UserRepository
import madrileno.utils.events.outbox.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant

class UserServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val now           = Instant.parse("2026-06-28T10:00:00Z")
  private given Clock[IO]   = TestGivens.fixedClock(now)
  private given UUIDGen[IO] = TestGivens.deterministicUUIDs()

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private lazy val userRepository     = new UserRepository
  private lazy val outboxRepository   = new OutboxRepository
  private lazy val deliveryRepository = new OutboxDeliveryRepository
  private lazy val schedulerClient    = Scheduler(transactor, SchedulerConfig()).client
  private lazy val dispatcher         = new OutboxDispatcher(Nil, outboxRepository, deliveryRepository, transactor, OutboxConfig())
  private lazy val outbox             = new Outbox(outboxRepository, deliveryRepository, schedulerClient, dispatcher)
  private lazy val service            = new UserService(userRepository, outbox, transactor)

  "UserService" should {
    "getCurrentUser returns the user for an existing id and None for a missing one" in {
      val user = TestData.user()
      for {
        _       <- transactor.inSession(userRepository.create(user, now))
        fetched <- service.getCurrentUser(user.id)
        absent  <- service.getCurrentUser(TestData.randomUserId())
      } yield {
        fetched.map(_.id) shouldBe Some(user.id)
        fetched.flatMap(_.fullName) shouldBe user.fullName
        fetched.flatMap(_.emailAddress) shouldBe user.emailAddress
        fetched.map(_.emailVerified) shouldBe Some(user.emailVerified)
        absent shouldBe None
      }
    }

    "deleteAccount anonymizes and publishes exactly one event — idempotently" in {
      val user = TestData.user()
      for {
        _      <- transactor.inSession(userRepository.create(user, now))
        first  <- service.deleteAccount(user.id)
        second <- service.deleteAccount(user.id)
        events <- transactor.inSession(outboxRepository.findByAggregate("user", user.id.unwrap))
        found  <- transactor.inSession(userRepository.find(user.id))
      } yield {
        first shouldBe DeleteAccountResult.Deleted
        second shouldBe DeleteAccountResult.AlreadyDeleted
        events should have size 1
        events.head.eventType shouldBe "user-account-deleted.v1"
        found shouldBe None
      }
    }
  }
}
