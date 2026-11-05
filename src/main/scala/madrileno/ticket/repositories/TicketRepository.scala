package madrileno.ticket.repositories

import cats.effect.IO
import madrileno.ticket.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class TicketRow(
  id: TicketId,
  name: TicketName,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def toTicket: Ticket = {
    import io.scalaland.chimney.dsl.*
    this.into[Ticket].transform
  }
}

private[repositories] object TicketRow {
  def apply(ticket: Ticket): TicketRow = {
    import io.scalaland.chimney.dsl.*
    ticket.into[TicketRow].transform
  }
}

private[repositories] object TicketRowTable extends Table[TicketRow]("ticket") with IdTable[TicketRow, TicketId] with SoftDeleteTable {
  override val id: Column[TicketId]               = column("id", uuid.as[TicketId])
  val name: Column[TicketName]                    = column("name", text.as[TicketName])
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[TicketRow]) =
    (id, name, createdAt, updatedAt, deletedAt)
}

private[repositories] final case class TicketRowFilter(id: SqlPredicate[TicketId] = p.any, deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (TicketRowTable.id, TicketRowTable.deletedAt))
}

class TicketRepository {
  def save(ticket: Ticket): DB[Unit] =
    repository.create(TicketRow(ticket)).void

  def find(id: TicketId): DB[Option[Ticket]] =
    repository.findById(id).map(_.map(_.toTicket))

  def update[E](id: TicketId, f: Ticket => Either[E, Ticket]): DBInTransaction[Option[Either[E, Ticket]]] =
    repository.findById(id, Lock.ForUpdate).map(_.map(_.toTicket)).flatMap {
      case None        => IO.pure(None)
      case Some(value) =>
        f(value) match {
          case Left(e)        => IO.pure(Some(Left(e)))
          case Right(updated) => repository.update(TicketRow(updated)).as(Some(Right(updated)))
        }
    }

  def softDelete(id: TicketId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  private val repository
    : IdRepository[TicketRow, TicketId] & SoftDeleteRepository[TicketRow, TicketId] & FilteringRepository[TicketRow, TicketRowFilter] =
    new IdRepository[TicketRow, TicketId](_.id) with SoftDeleteRepository[TicketRow, TicketId] with FilteringRepository[TicketRow, TicketRowFilter] {
      override val table: TicketRowTable.type = TicketRowTable
    }
}
