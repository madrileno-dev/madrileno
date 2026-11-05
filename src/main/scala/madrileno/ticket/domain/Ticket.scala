package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type TicketId = UUID
object TicketId extends Opaque[TicketId, UUID]

opaque type TicketName = String
object TicketName extends Opaque[TicketName, String] {
  override def validate(value: String): Either[String, TicketName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Ticket name must not be empty")
  }
}

enum RenameRejection {
  case NameUnchanged
}

final case class Ticket(
  id: TicketId,
  name: TicketName,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {

  def rename(newName: TicketName, now: Instant): Either[RenameRejection, Ticket] =
    if (newName == name) Left(RenameRejection.NameUnchanged)
    else Right(copy(name = newName, updatedAt = now))
}

object Ticket {
  def create(
    id: TicketId,
    name: TicketName,
    now: Instant
  ): Ticket =
    Ticket(id = id, name = name, createdAt = now, updatedAt = now, deletedAt = None)
}
