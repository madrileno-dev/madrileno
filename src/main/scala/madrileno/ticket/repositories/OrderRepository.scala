package madrileno.ticket.repositories

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class OrderRow(
  id: OrderId,
  eventId: EventId,
  customerId: UserId,
  createdAt: Instant)

private[repositories] object OrderRow {
  def apply(order: Order): OrderRow = order.into[OrderRow].transform
}

private[repositories] object OrderRowTable extends Table[OrderRow]("ticket_order") with IdTable[OrderRow, OrderId] {
  override val id: Column[OrderId] = column("id", uuid.as[OrderId])
  val eventId: Column[EventId]     = column("event_id", uuid.as[EventId])
  val customerId: Column[UserId]   = column("customer_id", uuid.as[UserId])
  val createdAt: Column[Instant]   = column("created_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[OrderRow]) = (id, eventId, customerId, createdAt)
}

class OrderRepository {

  // Inserts a NEW order. Its tickets are saved separately through TicketRepository, in the same transaction.
  def save(order: Order): DB[Unit] =
    repository.create(OrderRow(order)).void

  private val repository: IdRepository[OrderRow, OrderId] =
    new IdRepository[OrderRow, OrderId](_.id) {
      override val table: OrderRowTable.type = OrderRowTable
    }
}
