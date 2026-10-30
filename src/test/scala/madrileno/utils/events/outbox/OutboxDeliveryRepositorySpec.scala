package madrileno.utils.events.outbox

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import io.circe.Json
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.db.transactor.DBInTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

class OutboxDeliveryRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val outbox   = new OutboxRepository
  private lazy val delivery = new OutboxDeliveryRepository

  private def event(eventType: String): DomainEvent =
    DomainEvent(
      id = DomainEventId(TestData.randomUuid()),
      eventType = eventType,
      aggregateType = "sample",
      aggregateId = TestData.randomUuid(),
      payload = Json.obj("k" -> Json.fromString("v")),
      occurredAt = Instant.parse("2026-06-28T10:00:00Z")
    )

  "OutboxDeliveryRepository" should {
    "openDelivery is idempotent on (event_id, consumer)" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        first  <- delivery.openDelivery(e.id, "billing", now)
        second <- delivery.openDelivery(e.id, "billing", now)
        status <- delivery.lockForDelivery(e.id, "billing")
      } yield {
        first shouldBe true
        second shouldBe false
        status shouldBe Some(DeliveryStatus.Pending)
      }
    }

    "markCompleted and markFailed transition status" in withRollback {
      val e1  = event("sample-event.v1")
      val e2  = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _  <- outbox.append(e1)
        _  <- outbox.append(e2)
        _  <- delivery.openDelivery(e1.id, "billing", now)
        _  <- delivery.openDelivery(e2.id, "billing", now)
        _  <- delivery.markCompleted(e1.id, "billing", now)
        _  <- delivery.markFailed(e2.id, "billing", Some("boom"), now)
        s1 <- delivery.lockForDelivery(e1.id, "billing")
        s2 <- delivery.lockForDelivery(e2.id, "billing")
      } yield {
        s1 shouldBe Some(DeliveryStatus.Completed)
        s2 shouldBe Some(DeliveryStatus.Failed)
      }
    }

    "deliveriesMissingFor returns only events of subscribed types with no ledger row for the consumer" in withRollback {
      val matching  = event("order-created.v1")
      val otherType = event("user-deleted.v1")
      val already   = event("order-created.v1")
      val now       = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _       <- outbox.append(matching)
        _       <- outbox.append(otherType)
        _       <- outbox.append(already)
        _       <- delivery.openDelivery(already.id, "billing", now)
        missing <- delivery.deliveriesMissingFor("billing", List("order-created.v1"), 100)
      } yield {
        missing.map(_.id) should contain(matching.id)
        missing.map(_.id) should not contain otherType.id
        missing.map(_.id) should not contain already.id
      }
    }

    "pendingAfter pages through the pending deliveries and skips the finished ones" in withRollback {
      val e1  = event("sample-event.v1")
      val e2  = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _ <- outbox.append(e1)
        _ <- outbox.append(e2)
        _ <- List(e1, e2).flatMap(e => List(e.id -> "billing", e.id -> "audit")).traverse_((id, consumer) => delivery.openDelivery(id, consumer, now))
        _ <- delivery.markCompleted(e2.id, "audit", now)
        first  <- delivery.pendingAfter(None, 2)
        second <- delivery.pendingAfter(first.lastOption, 2)
        third  <- delivery.pendingAfter(second.lastOption, 2)
      } yield {
        (first.size, second.size, third.size) shouldBe (2, 1, 0)
        first ++ second should contain theSameElementsAs List((e1.id, "billing"), (e1.id, "audit"), (e2.id, "billing"))
      }
    }

    "recordError sets last_error without touching pending status, and markFailed(None) preserves it" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        _      <- delivery.openDelivery(e.id, "billing", now)
        _      <- delivery.recordError(e.id, "billing", "attempt 1 boom", now)
        status <- delivery.lockForDelivery(e.id, "billing")
        _      <- delivery.markFailed(e.id, "billing", None, now)
        error  <- lastError(e.id, "billing")
      } yield {
        status shouldBe Some(DeliveryStatus.Pending)
        error shouldBe Some("attempt 1 boom")
      }
    }

    "recordError does not touch terminal rows and loadEvent round-trips" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        _      <- delivery.openDelivery(e.id, "billing", now)
        _      <- delivery.markCompleted(e.id, "billing", now)
        _      <- delivery.recordError(e.id, "billing", "late error", now)
        error  <- lastError(e.id, "billing")
        loaded <- outbox.loadEvent(e.id)
        absent <- outbox.loadEvent(DomainEventId(TestData.randomUuid()))
      } yield {
        error shouldBe None
        loaded shouldBe Some(e)
        absent shouldBe None
      }
    }

    "markCompleted clears a previously recorded last_error" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _   <- outbox.append(e)
        _   <- delivery.openDelivery(e.id, "billing", now)
        _   <- delivery.recordError(e.id, "billing", "transient boom", now)
        _   <- delivery.markCompleted(e.id, "billing", now)
        err <- lastError(e.id, "billing")
      } yield err shouldBe None
    }
  }

  private def lastError(eventId: DomainEventId, consumer: String): DBInTransaction[Option[String]] = {
    val session = summon[Session[IO]]
    session.unique(sql"""SELECT last_error FROM outbox_delivery
                         WHERE event_id = $uuid AND consumer = $text""".query(text.opt))((eventId.unwrap, consumer))
  }
}
