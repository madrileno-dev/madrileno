package madrileno.utils.events.outbox

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import scala.concurrent.duration.*

class OutboxEndToEndSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val outboxRepository   = new OutboxRepository
  private lazy val deliveryRepository = new OutboxDeliveryRepository
  private val schedulerConfig         = SchedulerConfig(pollingInterval = 100.millis, retryBaseDelay = 100.millis)
  private val outboxConfig            = OutboxConfig(deliveryMaxRetries = Some(1))

  private def waitUntil(cond: IO[Boolean], timeout: FiniteDuration = 10.seconds): IO[Unit] = {
    def poll(remaining: FiniteDuration): IO[Unit] =
      cond.flatMap {
        case true                            => IO.unit
        case false if remaining <= 0.seconds => IO.raiseError(new RuntimeException("timed out"))
        case false                           => IO.sleep(50.millis) *> poll(remaining - 50.millis)
      }
    poll(timeout)
  }

  private def statusOf(id: DomainEventId, consumer: String): IO[Option[DeliveryStatus]] =
    transactor.inTransaction(deliveryRepository.lockForDelivery(id, consumer))

  private def lastError(id: DomainEventId, consumer: String): IO[Option[String]] =
    transactor.inTransaction {
      val session = summon[Session[IO]]
      session.unique(sql"""SELECT last_error FROM outbox_delivery
                           WHERE event_id = $uuid AND consumer = $text""".query(text.opt))((id.unwrap, consumer))
    }

  "outbox end to end" should {
    "deliver a published event to the subscribed consumer exactly once" in {
      for {
        seen <- Ref.of[IO, Int](0)
        subs       = List(OutboxSubscription[DispatchEvent]("billing")(_ => seen.update(_ + 1).as(Reaction.Done)))
        dispatcher = new OutboxDispatcher(subs, outboxRepository, deliveryRepository, transactor, outboxConfig)
        scheduler  = Scheduler(transactor, schedulerConfig)
        outbox     = new Outbox(outboxRepository, deliveryRepository, scheduler.client, dispatcher)
        payload    = DispatchEvent(TestData.randomUuid(), "e2e")
        _ <- scheduler.run(recurringTasks = Nil, oneTimeTasks = dispatcher.deliveryTasks, customTasks = Nil).use { _ =>
               for {
                 _   <- transactor.inTransaction(outbox.publishTransactionally(payload))
                 evs <- transactor.inSession(outboxRepository.findByAggregate("dispatch", payload.id))
                 id = evs.head.id
                 _ <- waitUntil(statusOf(id, "billing").map(_.contains(DeliveryStatus.Completed)))
               } yield ()
             }
        n <- seen.get
      } yield n shouldBe 1
    }

    "dead-letter a permanently failing consumer with the causal error preserved" in {
      for {
        attempts <- Ref.of[IO, Int](0)
        subs       = List(OutboxSubscription[DispatchEvent]("stock")(_ => attempts.update(_ + 1).as(Reaction.Retry("stock service down"))))
        dispatcher = new OutboxDispatcher(subs, outboxRepository, deliveryRepository, transactor, outboxConfig)
        scheduler  = Scheduler(transactor, schedulerConfig)
        outbox     = new Outbox(outboxRepository, deliveryRepository, scheduler.client, dispatcher)
        payload    = DispatchEvent(TestData.randomUuid(), "doomed")
        id <- scheduler.run(recurringTasks = Nil, oneTimeTasks = dispatcher.deliveryTasks, customTasks = Nil).use { _ =>
                for {
                  _   <- transactor.inTransaction(outbox.publishTransactionally(payload))
                  evs <- transactor.inSession(outboxRepository.findByAggregate("dispatch", payload.id))
                  id = evs.head.id
                  _ <- waitUntil(statusOf(id, "stock").map(_.contains(DeliveryStatus.Failed)))
                } yield id
              }
        n   <- attempts.get
        err <- lastError(id, "stock")
      } yield {
        n shouldBe 2
        err shouldBe Some("stock service down")
      }
    }
  }
}
