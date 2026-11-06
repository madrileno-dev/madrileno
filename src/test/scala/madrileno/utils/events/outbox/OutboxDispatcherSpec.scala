package madrileno.utils.events.outbox

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.events.EventCodec
import madrileno.utils.observability.TelemetryContext
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

final case class DispatchEvent(id: UUID, tag: String) derives EventCodec

object DispatchEvent {
  given ValueClassLike[UUID, UUID] = new ValueClassLike(identity, identity)

  given DomainEventDescriptor[DispatchEvent] = DomainEventDescriptor("dispatch-event.v1", "dispatch", _.id)
}

class OutboxDispatcherSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val outbox   = new OutboxRepository
  private lazy val delivery = new OutboxDeliveryRepository
  private val now           = Instant.parse("2026-07-11T10:00:00Z")

  private def storedEvent(payload: DispatchEvent): DomainEvent =
    DomainEvent(
      id = DomainEventId(TestData.randomUuid()),
      eventType = "dispatch-event.v1",
      aggregateType = "dispatch",
      aggregateId = payload.id,
      payload = summon[DomainEventDescriptor[DispatchEvent]].codec.encode(payload),
      occurredAt = now
    )

  private def dispatcher(subs: OutboxSubscription*): OutboxDispatcher =
    new OutboxDispatcher(subs.toList, outbox, delivery, transactor, OutboxConfig())

  private def runDelivery(
    d: OutboxDispatcher,
    consumer: String,
    eventId: DomainEventId
  ): IO[Unit] = {
    val task = d.deliveryTasks.find(_.descriptor.taskName == OutboxDeliveryTasks.taskName(consumer)).get
    task.execution(d.instanceFor(consumer, eventId))
  }

  private def seed(e: DomainEvent, consumer: String): IO[Unit] =
    transactor.inTransaction {
      outbox.append(e).flatMap(_ => delivery.openDelivery(e.id, consumer, now)).map(_ => ())
    }

  private def lastError(eventId: DomainEventId, consumer: String): IO[Option[String]] =
    transactor.inTransaction {
      val session = summon[Session[IO]]
      session.unique(sql"""SELECT last_error FROM outbox_delivery
                           WHERE event_id = $uuid AND consumer = $text""".query(text.opt))((eventId.unwrap, consumer))
    }

  "OutboxDispatcher" should {
    "run the handler and mark completed in one delivery" in {
      for {
        seen <- Ref.of[IO, List[String]](Nil)
        e = storedEvent(DispatchEvent(TestData.randomUuid(), "hello"))
        d = dispatcher(OutboxSubscription[DispatchEvent]("billing")(ev => seen.update(_ :+ ev.tag).as(Reaction.Done)))
        _    <- seed(e, "billing")
        _    <- runDelivery(d, "billing", e.id)
        tags <- seen.get
        st   <- transactor.inTransaction(delivery.lockForDelivery(e.id, "billing"))
      } yield {
        tags shouldBe List("hello")
        st shouldBe Some(DeliveryStatus.Completed)
      }
    }

    "no-op on a terminal row (exactly-once guard)" in {
      for {
        seen <- Ref.of[IO, Int](0)
        e = storedEvent(DispatchEvent(TestData.randomUuid(), "x"))
        d = dispatcher(OutboxSubscription[DispatchEvent]("billing")(_ => seen.update(_ + 1).as(Reaction.Done)))
        _ <- seed(e, "billing")
        _ <- runDelivery(d, "billing", e.id)
        _ <- runDelivery(d, "billing", e.id)
        n <- seen.get
      } yield n shouldBe 1
    }

    "Drop marks failed with the reason and commits prior writes" in {
      val e = storedEvent(DispatchEvent(TestData.randomUuid(), "poison"))
      val d = dispatcher(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Drop("cannot process"))))
      for {
        _   <- seed(e, "billing")
        _   <- runDelivery(d, "billing", e.id)
        st  <- transactor.inTransaction(delivery.lockForDelivery(e.id, "billing"))
        err <- lastError(e.id, "billing")
      } yield {
        st shouldBe Some(DeliveryStatus.Failed)
        err shouldBe Some("cannot process")
      }
    }

    "Retry raises, rolls back the handler's writes, and records last_error status-preservingly" in {
      val e = storedEvent(DispatchEvent(TestData.randomUuid(), "flaky"))
      val d = dispatcher(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Retry("downstream 503"))))
      for {
        _      <- seed(e, "billing")
        result <- runDelivery(d, "billing", e.id).attempt
        st     <- transactor.inTransaction(delivery.lockForDelivery(e.id, "billing"))
        err    <- lastError(e.id, "billing")
      } yield {
        result match {
          case Left(_: OutboxRetryRequested) => ()
          case _                             => fail(s"expected Left(OutboxRetryRequested), got $result")
        }
        st shouldBe Some(DeliveryStatus.Pending)
        err shouldBe Some("downstream 503")
      }
    }

    "undecodable payload is poison: marked failed, not retried" in {
      val bad = DomainEvent(
        id = DomainEventId(TestData.randomUuid()),
        eventType = "dispatch-event.v1",
        aggregateType = "dispatch",
        aggregateId = TestData.randomUuid(),
        payload = Json.obj("garbage" -> true.asJson),
        occurredAt = now
      )
      val d = dispatcher(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Done)))
      for {
        _  <- seed(bad, "billing")
        _  <- runDelivery(d, "billing", bad.id)
        st <- transactor.inTransaction(delivery.lockForDelivery(bad.id, "billing"))
      } yield st shouldBe Some(DeliveryStatus.Failed)
    }

    "onAbandon dead-letters preserving the recorded error" in {
      val e    = storedEvent(DispatchEvent(TestData.randomUuid(), "doomed"))
      val d    = dispatcher(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Retry("boom"))))
      val task = d.deliveryTasks.head
      for {
        _   <- seed(e, "billing")
        _   <- runDelivery(d, "billing", e.id).attempt
        _   <- transactor.inTransaction(task.onAbandon.get(d.instanceFor("billing", e.id)))
        st  <- transactor.inTransaction(delivery.lockForDelivery(e.id, "billing"))
        err <- lastError(e.id, "billing")
      } yield {
        st shouldBe Some(DeliveryStatus.Failed)
        err shouldBe Some("boom")
      }
    }

    "reject duplicate (consumer, eventType) subscriptions at construction" in {
      val sub = OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Done))
      IO(intercept[IllegalArgumentException](dispatcher(sub, sub))).map(_.getMessage should include("billing"))
    }
  }
}
