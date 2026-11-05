package madrileno.ticket.services

import cats.effect.IO
import madrileno.ticket.domain.{Ticket, TicketId}
import madrileno.ticket.repositories.TicketRepository
import madrileno.utils.db.transactor.Transactor

// SCAFFOLD-TODO: this is a stub. Replace with the actual use cases for this aggregate
// (create, update, list, business workflows...). Delete this comment when you do.
class TicketService(ticketRepository: TicketRepository, transactor: Transactor) {
  def find(id: TicketId): IO[Option[Ticket]] =
    transactor.inSession(ticketRepository.find(id))
}
