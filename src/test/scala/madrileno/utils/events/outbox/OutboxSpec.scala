package madrileno.utils.events.outbox

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.circe.Json
import io.circe.syntax.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.utils.db.transactor.DBInTransaction
import madrileno.utils.events.EventCodec
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.kebs.core.macros.ValueClassLike
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.UUID

final case class SampleEvent(userId: UUID, name: String) derives EventCodec

object SampleEvent {
  given ValueClassLike[UUID, UUID] = new ValueClassLike(identity, identity)

  given DomainEventDescriptor[SampleEvent] = DomainEventDescriptor("sample-event.v1", "sample", _.userId)
}

class OutboxSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val fixedInstant  = Instant.parse("2026-06-28T10:00:00Z")
  private given Clock[IO]   = TestGivens.fixedClock(fixedInstant)
  private given UUIDGen[IO] = TestGivens.deterministicUUIDs()

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val repository         = new OutboxRepository
  private lazy val deliveryRepository = new OutboxDeliveryRepository
  private lazy val schedulerClient    = Scheduler(transactor, SchedulerConfig()).client

  private val subscriptions = List(
    OutboxSubscription[SampleEvent]("billing")(_ => IO.pure(Reaction.Done)),
    OutboxSubscription[SampleEvent]("stock")(_ => IO.pure(Reaction.Done))
  )

  private lazy val dispatcher = new OutboxDispatcher(subscriptions, repository, deliveryRepository, transactor, OutboxConfig())

  private lazy val outbox = new Outbox(repository, deliveryRepository, schedulerClient, dispatcher)

  private def scheduledCount(taskName: String): DBInTransaction[Long] = {
    val session = summon[Session[IO]]
    session.unique(sql"SELECT count(*) FROM scheduled_task WHERE task_name = $text".query(int8))(taskName)
  }

  "Outbox.publishTransactionally" should {
    "stamp a UUIDv7 id + occurred_at and append the event with the versioned type" in withRollback {
      val event = SampleEvent(TestData.randomUuid(), "alice")
      for {
        _     <- outbox.publishTransactionally(event)
        found <- repository.findByAggregate("sample", event.userId)
      } yield {
        found should have size 1
        val stored = found.head
        stored.eventType shouldBe "sample-event.v1"
        stored.aggregateType shouldBe "sample"
        stored.aggregateId shouldBe event.userId
        stored.payload shouldBe Json.obj("userId" -> event.userId.asJson, "name" -> "alice".asJson)
        stored.occurredAt shouldBe fixedInstant
        stored.id.unwrap.version() shouldBe 7
      }
    }

    "not persist the event when the surrounding transaction fails" in {
      val event = SampleEvent(TestData.randomUuid(), "bob")
      for {
        result <- transactor.inTransaction {
                    outbox.publishTransactionally(event) *> IO.raiseError[Unit](new RuntimeException("boom"))
                  }.attempt
        found        <- transactor.inSession(repository.findByAggregate("sample", event.userId))
        billingCount <- transactor.inTransaction(scheduledCount(OutboxDeliveryTasks.taskName("billing")))
      } yield {
        result.isLeft shouldBe true
        found shouldBe empty
        billingCount shouldBe 0L
      }
    }

    "fan out to every subscribed consumer: ledger rows + delivery tasks, atomically" in withRollback {
      val event = SampleEvent(TestData.randomUuid(), "carol")
      for {
        _        <- outbox.publishTransactionally(event)
        appended <- repository.findByAggregate("sample", event.userId)
        id = appended.head.id
        billing  <- deliveryRepository.lockForDelivery(id, "billing")
        stock    <- deliveryRepository.lockForDelivery(id, "stock")
        tBilling <- scheduledCount(OutboxDeliveryTasks.taskName("billing"))
        tStock   <- scheduledCount(OutboxDeliveryTasks.taskName("stock"))
      } yield {
        billing shouldBe Some(DeliveryStatus.Pending)
        stock shouldBe Some(DeliveryStatus.Pending)
        tBilling shouldBe 1L
        tStock shouldBe 1L
      }
    }

    "publish with no subscribers appends only (Phase 1 behavior)" in withRollback {
      val event   = SampleEvent(TestData.randomUuid(), "dave")
      val noSubs  = new OutboxDispatcher(Nil, repository, deliveryRepository, transactor, OutboxConfig())
      val outbox0 = new Outbox(repository, deliveryRepository, schedulerClient, noSubs)
      for {
        _     <- outbox0.publishTransactionally(event)
        found <- repository.findByAggregate("sample", event.userId)
        b     <- deliveryRepository.lockForDelivery(found.head.id, "billing")
      } yield {
        found should have size 1
        b shouldBe None
      }
    }
  }
}
