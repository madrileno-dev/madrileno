package madrileno.ticket.services

import cats.effect.{Clock, IO}
import madrileno.ticket.domain.{ChargeId, PayRejection, Ticket, TicketId}
import madrileno.ticket.repositories.TicketRepository
import madrileno.user.domain.UserId
import madrileno.utils.db.transactor.Transactor

final case class PayTicketCommand(
  ticketId: TicketId,
  requesterId: UserId,
  chargeId: ChargeId)

enum PayTicketResult {
  case TicketPaid(ticket: Ticket)
  // Also returned for a ticket owned by someone else: a foreign ticket must be indistinguishable from a missing one.
  case TicketNotFound
  case TicketAlreadyPaid
  case TicketCancelled
  case TicketExpired
}

class TicketService(ticketRepository: TicketRepository, transactor: Transactor)(using Clock[IO]) {

  // Ownership is enforced here: a ticket owned by someone else is reported as None (-> 404), exactly like a
  // missing one, so the endpoint cannot be used to probe which ticket ids exist.
  def find(id: TicketId, requesterId: UserId): IO[Option[Ticket]] =
    transactor.inSession(ticketRepository.find(id)).map(_.filter(_.customerId == requesterId))

  def payTicket(command: PayTicketCommand): IO[PayTicketResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        ticketRepository.update(command.ticketId, _.pay(command.requesterId, command.chargeId, now)).map {
          case None => PayTicketResult.TicketNotFound
          // Deliberately the same result as a missing ticket: do not leak that the ticket exists.
          case Some(Left(PayRejection.NotOwner))         => PayTicketResult.TicketNotFound
          case Some(Left(PayRejection.AlreadyPaid))      => PayTicketResult.TicketAlreadyPaid
          case Some(Left(PayRejection.AlreadyCancelled)) => PayTicketResult.TicketCancelled
          case Some(Left(PayRejection.HoldExpired))      => PayTicketResult.TicketExpired
          case Some(Right(paid))                         => PayTicketResult.TicketPaid(paid)
        }
      }
    }
  }
}
