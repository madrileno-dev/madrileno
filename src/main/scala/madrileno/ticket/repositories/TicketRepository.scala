package madrileno.ticket.repositories

import cats.effect.IO
import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.time.Instant

// Money is flattened into two columns (price_minor, currency).
private[repositories] final case class TicketRow(
  id: TicketId,
  orderId: OrderId,
  seatId: SeatId,
  customerId: UserId,
  seatLabel: SeatLabel,
  priceMinor: Long,
  currency: Currency,
  status: TicketStatus,
  heldUntil: Instant,
  chargeId: Option[ChargeId],
  paidAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant) {

  // Throws on a row that violates Money's invariant, the same way opaque-type codecs throw on invalid
  // column values. The `price_minor > 0` CHECK constraint keeps such rows out of the table.
  private def price: Money =
    Money.from(priceMinor, currency).fold(error => throw new IllegalStateException(s"Corrupt price in ticket $id: $error"), identity)

  def toTicket: Ticket =
    this.into[Ticket].withFieldConst(_.price, price).transform
}

private[repositories] object TicketRow {
  def apply(ticket: Ticket): TicketRow =
    ticket
      .into[TicketRow]
      .withFieldConst(_.priceMinor, ticket.price.amountMinor)
      .withFieldConst(_.currency, ticket.price.currency)
      .transform
}

private[repositories] object TicketRowTable extends Table[TicketRow]("ticket") with IdTable[TicketRow, TicketId] {
  override val id: Column[TicketId] = column("id", uuid.as[TicketId])
  val orderId: Column[OrderId]      = column("order_id", uuid.as[OrderId])
  val seatId: Column[SeatId]        = column("seat_id", uuid.as[SeatId])
  val customerId: Column[UserId]    = column("customer_id", uuid.as[UserId])
  val seatLabel: Column[SeatLabel]  = column("seat_label", text.as[SeatLabel])
  val priceMinor: Column[Long]      = column("price_minor", int8)
  // asEnum stores the enum case name, so the SQL CHECK lists must match the case names exactly.
  val currency: Column[Currency]         = column("currency", text.asEnum[Currency])
  val status: Column[TicketStatus]       = column("status", text.asEnum[TicketStatus])
  val heldUntil: Column[Instant]         = column("held_until", timestamptz.asInstant)
  val chargeId: Column[Option[ChargeId]] = column("charge_id", text.as[ChargeId].opt)
  val paidAt: Column[Option[Instant]]    = column("paid_at", timestamptz.asInstant.opt)
  val createdAt: Column[Instant]         = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]         = column("updated_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[TicketRow]) =
    (id, orderId, seatId, customerId, seatLabel, priceMinor, currency, status, heldUntil, chargeId, paidAt, createdAt, updatedAt)
}

private[repositories] final case class TicketRowFilter(
  id: SqlPredicate[TicketId] = p.any,
  status: SqlPredicate[TicketStatus] = p.any,
  heldUntil: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment = fromPredicates(
    (id -> TicketRowTable.id, status -> TicketRowTable.status, heldUntil -> TicketRowTable.heldUntil)
  )
}

class TicketRepository {

  // Inserts a NEW ticket (a fresh hold). Changes to an existing ticket go through `update` only.
  // A second live (Held/Paid) ticket for the same seat fails with a unique violation on ticket_live_seat_uidx.
  def save(ticket: Ticket): DB[Unit] =
    repository.create(TicketRow(ticket)).void

  def find(id: TicketId): DB[Option[Ticket]] =
    repository.findById(id).map(_.map(_.toTicket))

  // The only mutation path: locks the row (FOR UPDATE), applies the domain transition and writes it back.
  // On Left nothing is written. None when the ticket does not exist.
  def update[E](id: TicketId, f: Ticket => Either[E, Ticket]): DBInTransaction[Option[Either[E, Ticket]]] =
    findForUpdate(id).flatMap {
      case None         => IO.pure(None)
      case Some(ticket) =>
        f(ticket) match {
          case Left(e)        => IO.pure(Some(Left(e)))
          case Right(updated) => repository.update(TicketRow(updated)).as(Some(Right(updated)))
        }
    }

  // Inclusive bound, matching the domain: a hold is already expired at now == heldUntil.
  def listExpiredHolds(now: Instant): DB[List[TicketId]] =
    repository
      .findByFilter(TicketRowFilter(status = p.equal(TicketStatus.Held), heldUntil = p.lessThanOrEqual(now)))
      .map(_.map(_.id))

  private def findForUpdate(id: TicketId): DBInTransaction[Option[Ticket]] =
    repository.findById(id, Lock.ForUpdate).map(_.map(_.toTicket))

  private val repository: IdRepository[TicketRow, TicketId] & FilteringRepository[TicketRow, TicketRowFilter] =
    new IdRepository[TicketRow, TicketId](_.id) with FilteringRepository[TicketRow, TicketRowFilter] {
      override val table: TicketRowTable.type = TicketRowTable
    }
}
