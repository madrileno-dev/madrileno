package madrileno.utils.events.outbox

import cats.effect.IO
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.task.TaskRowTable
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

enum DeliveryStatus {
  case Pending, Completed, Failed
}

private[outbox] final case class OutboxDeliveryRow(
  eventId: DomainEventId,
  consumer: String,
  status: DeliveryStatus,
  lastError: Option[String],
  createdAt: Instant,
  updatedAt: Instant)

private[outbox] object OutboxDeliveryTable extends Table[OutboxDeliveryRow]("outbox_delivery") {
  val eventId: Column[DomainEventId]    = column("event_id", uuid.as[DomainEventId])
  val consumer: Column[String]          = column("consumer", text)
  val status: Column[DeliveryStatus]    = column("status", text.asEnum[DeliveryStatus])
  val lastError: Column[Option[String]] = column("last_error", text.opt)
  val createdAt: Column[Instant]        = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]        = column("updated_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[OutboxDeliveryRow]) =
    (eventId, consumer, status, lastError, createdAt, updatedAt)
}

private[outbox] final case class OutboxDeliveryFilter(eventId: SqlPredicate[DomainEventId] = p.any, consumer: SqlPredicate[String] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (OutboxDeliveryTable.eventId, OutboxDeliveryTable.consumer))
}

class OutboxDeliveryRepository {
  private val T  = OutboxDeliveryTable
  private val DE = DomainEventTable
  private val ST = TaskRowTable

  def openDelivery(
    eventId: DomainEventId,
    consumer: String,
    now: Instant
  ): DBInTransaction[Boolean] =
    repository.insertIfAbsent(OutboxDeliveryRow(eventId, consumer, DeliveryStatus.Pending, None, now, now))

  def lockForDelivery(eventId: DomainEventId, consumer: String): DBInTransaction[Option[DeliveryStatus]] =
    repository
      .findOneByFilter(OutboxDeliveryFilter(eventId = p.equal(eventId), consumer = p.equal(consumer)), Lock.ForUpdate)
      .map(_.map(_.status))

  def markCompleted(
    eventId: DomainEventId,
    consumer: String,
    now: Instant
  ): DBInTransaction[Unit] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"""UPDATE ${T.n}
              SET ${T.status.n} = ${T.status.c}, ${T.lastError.n} = NULL, ${T.updatedAt.n} = ${T.updatedAt.c}
              WHERE ${T.eventId.n} = ${T.eventId.c} AND ${T.consumer.n} = ${T.consumer.c}""".command)(
        (DeliveryStatus.Completed, now, eventId, consumer)
      )
      .void
  }

  def markFailed(
    eventId: DomainEventId,
    consumer: String,
    error: Option[String],
    now: Instant
  ): DBInTransaction[Unit] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"""UPDATE ${T.n}
              SET ${T.status.n} = ${T.status.c}, ${T.lastError.n} = COALESCE(${T.lastError.c}, ${T.lastError.n}), ${T.updatedAt.n} = ${T.updatedAt.c}
              WHERE ${T.eventId.n} = ${T.eventId.c} AND ${T.consumer.n} = ${T.consumer.c}""".command)(
        (DeliveryStatus.Failed, error, now, eventId, consumer)
      )
      .void
  }

  def recordError(
    eventId: DomainEventId,
    consumer: String,
    error: String,
    now: Instant
  ): DB[Unit] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"""UPDATE ${T.n}
              SET ${T.lastError.n} = ${T.lastError.c}, ${T.updatedAt.n} = ${T.updatedAt.c}
              WHERE ${T.eventId.n} = ${T.eventId.c} AND ${T.consumer.n} = ${T.consumer.c} AND ${T.status.n} = ${T.status.c}""".command)(
        (Some(error), now, eventId, consumer, DeliveryStatus.Pending)
      )
      .void
  }

  def deliveriesMissingFor(
    consumer: String,
    eventTypes: List[String],
    limit: Int
  ): DB[List[DomainEvent]] = {
    val session = summon[Session[IO]]
    if (eventTypes.isEmpty) IO.pure(Nil)
    else {
      val types = text.list(eventTypes.length)
      session.execute(sql"""SELECT ${DE.*("de")} FROM ${DE.n} de
              WHERE de.${DE.eventType.n} IN ($types)
                AND NOT EXISTS (
                  SELECT 1 FROM ${T.n} od
                  WHERE od.${T.eventId.n} = de.${DE.id.n} AND od.${T.consumer.n} = ${T.consumer.c}
                )
              ORDER BY de.${DE.id.n}
              LIMIT $int4""".query(DE.c))((eventTypes, consumer, limit))
    }
  }

  def pendingWithoutTask(taskNamePrefix: String, limit: Int): DB[List[(DomainEventId, String)]] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"""SELECT ${T.eventId.n("od")}, ${T.consumer.n("od")} FROM ${T.n} od
              WHERE ${T.status.n("od")} = ${T.status.c}
                AND NOT EXISTS (
                  SELECT 1 FROM ${ST.n} st
                  WHERE ${ST.taskName.n("st")} = $text || ${T.consumer.n("od")}
                    AND ${ST.taskInstance.n("st")} = ${T.eventId.n("od")}::text
                )
              ORDER BY ${T.updatedAt.n("od")}
              LIMIT $int4""".query(T.eventId.c ~ T.consumer.c))((DeliveryStatus.Pending, taskNamePrefix, limit))
      .map(_.map { case id ~ consumer => (id, consumer) })
  }

  private val repository: FilteringRepository[OutboxDeliveryRow, OutboxDeliveryFilter] =
    new FilteringRepository[OutboxDeliveryRow, OutboxDeliveryFilter] {
      override val table: OutboxDeliveryTable.type = OutboxDeliveryTable
    }
}
