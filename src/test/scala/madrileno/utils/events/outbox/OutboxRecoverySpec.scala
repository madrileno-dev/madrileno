package madrileno.utils.events.outbox

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import madrileno.support.{RecordingTracer, TestData, TestTransactor}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

class OutboxRecoverySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private lazy val outbox          = new OutboxRepository
  private lazy val delivery        = new OutboxDeliveryRepository
  private lazy val schedulerClient = Scheduler(transactor, SchedulerConfig()).client
  private val now                  = Instant.parse("2026-07-11T10:00:00Z")

  private def recovery(d: OutboxDispatcher): OutboxRecovery =
    new OutboxRecovery(d, delivery, schedulerClient, transactor, OutboxConfig())

  private def subscribed: OutboxDispatcher =
    new OutboxDispatcher(
      List(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Done))),
      outbox,
      delivery,
      transactor,
      OutboxConfig()
    )

  private def scheduledCount(eventIds: List[DomainEventId]): IO[Long] =
    eventIds
      .traverse { id =>
        transactor.inSession {
          val session = summon[Session[IO]]
          session.unique(sql"SELECT count(*) FROM scheduled_task WHERE task_instance = $text".query(int8))(id.unwrap.toString)
        }
      }
      .map(_.sum)

  private def pendingStatus(id: DomainEventId): IO[Option[DeliveryStatus]] =
    transactor.inTransaction(delivery.lockForDelivery(id, "billing"))

  "OutboxRecovery.recoverOnce" should {
    "case a: creates ledger rows + tasks for events a consumer never got, only for subscribed types" in {
      val d       = subscribed
      val matched =
        DomainEvent(DomainEventId(TestData.randomUuid()), "dispatch-event.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      val other = DomainEvent(DomainEventId(TestData.randomUuid()), "unrelated.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      for {
        _  <- transactor.inTransaction(outbox.append(matched) *> outbox.append(other))
        _  <- recovery(d).recoverOnce
        s1 <- pendingStatus(matched.id)
        s2 <- pendingStatus(other.id)
        n  <- scheduledCount(List(matched.id, other.id))
        _  <- recovery(d).recoverOnce
        n2 <- scheduledCount(List(matched.id, other.id))
      } yield {
        s1 shouldBe Some(DeliveryStatus.Pending)
        s2 shouldBe None
        n shouldBe 1L
        n2 shouldBe 1L
      }
    }

    "case b: re-enqueues a pending ledger row whose task vanished" in {
      val d = subscribed
      val e = DomainEvent(DomainEventId(TestData.randomUuid()), "dispatch-event.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      for {
        _ <- transactor.inTransaction(outbox.append(e) *> delivery.openDelivery(e.id, "billing", now).void)
        _ <- recovery(d).recoverOnce
        n <- scheduledCount(List(e.id))
      } yield n shouldBe 1L
    }

    "wrap a pass in an outbox.recover span carrying the opened and re-enqueued counts" in {
      val d = subscribed
      val e = DomainEvent(DomainEventId(TestData.randomUuid()), "dispatch-event.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      for {
        recorder <- RecordingTracer.create
        _        <- transactor.inTransaction(outbox.append(e))
        _        <- new OutboxRecovery(d, delivery, schedulerClient, transactor, OutboxConfig())(using recorder.context).recoverOnce
        spans    <- recorder.spansNamed("outbox.recover")
      } yield {
        spans.map(_.getParentSpanContext.isValid) shouldBe List(false)
        spans.map(RecordingTracer.longAttribute(_, "outbox.opened")) shouldBe List(Some(1L))
        spans.map(RecordingTracer.longAttribute(_, "outbox.reenqueued")) shouldBe List(Some(0L))
      }
    }
  }
}
