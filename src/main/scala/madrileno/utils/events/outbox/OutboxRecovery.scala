package madrileno.utils.events.outbox

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.SchedulerClient
import org.typelevel.otel4s.Attribute

class OutboxRecovery(
  dispatcher: OutboxDispatcher,
  deliveryRepository: OutboxDeliveryRepository,
  schedulerClient: SchedulerClient,
  transactor: Transactor,
  config: OutboxConfig
)(using TelemetryContext)
    extends LoggingSupport {

  def recoverOnce: IO[Unit] =
    summon[TelemetryContext].tracer.span("outbox.recover").use { span =>
      for {
        opened     <- missingLedgerRows
        reenqueued <- strandedPendingRows
        _          <- span.addAttributes(Attribute("outbox.opened", opened), Attribute("outbox.reenqueued", reenqueued))
        _          <- report(opened, reenqueued)
      } yield ()
    }

  def run: Resource[IO, Unit] =
    (recoverOnce.handleErrorWith(e => logger.error(e)("outbox recovery pass failed")) *>
      IO.sleep(config.recoveryInterval)).foreverM.background.void

  private def missingLedgerRows: IO[Long] =
    dispatcher.consumers.foldMapM { consumer =>
      transactor
        .inSession(deliveryRepository.deliveriesMissingFor(consumer, dispatcher.eventTypesFor(consumer), config.recoveryBatchSize))
        .flatMap(_.foldMapM(openAndSchedule(consumer, _)))
    }

  private def openAndSchedule(consumer: String, event: DomainEvent): IO[Long] =
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction {
        deliveryRepository.openDelivery(event.id, consumer, now).flatMap {
          case true  => schedulerClient.scheduleTransactionally(dispatcher.instanceFor(consumer, event.id)).as(1L)
          case false => IO.pure(0L)
        }
      }
    }

  private def strandedPendingRows: IO[Long] =
    sweep(None, 0L, Map.empty).flatMap { (reenqueued, unregistered) =>
      unregistered.toList.sorted
        .traverse_ { (consumer, count) =>
          logger.warn(s"$count pending outbox deliveries for unregistered consumer $consumer, leaving them for drain")
        }
        .as(reenqueued)
    }

  private def sweep(
    after: Option[(DomainEventId, String)],
    reenqueued: Long,
    unregistered: Map[String, Int]
  ): IO[(Long, Map[String, Int])] =
    transactor.inSession(deliveryRepository.pendingAfter(after, config.recoveryBatchSize)).flatMap { page =>
      val (known, unknown) = page.partition((_, consumer) => dispatcher.consumers.contains(consumer))
      val skipped          = unknown.foldLeft(unregistered)((counts, delivery) => counts.updatedWith(delivery._2)(n => Some(n.getOrElse(0) + 1)))
      schedulerClient.scheduleIfAbsent(known.map((eventId, consumer) => dispatcher.instanceFor(consumer, eventId))).flatMap { scheduled =>
        if (page.isEmpty || page.sizeIs < config.recoveryBatchSize) IO.pure((reenqueued + scheduled, skipped))
        else sweep(page.lastOption, reenqueued + scheduled, skipped)
      }
    }

  private def report(opened: Long, reenqueued: Long): IO[Unit] =
    if (opened == 0 && reenqueued == 0) IO.unit
    else
      logger.info(s"outbox recovery: opened $opened missing deliveries, re-enqueued $reenqueued stranded deliveries") *>
        summon[TelemetryContext].meter.counter[Long]("outbox.recovered").create.flatMap { counter =>
          counter.add(opened, Attribute("case", "missing_ledger")).whenA(opened > 0) *>
            counter.add(reenqueued, Attribute("case", "stranded_task")).whenA(reenqueued > 0)
        }
}
