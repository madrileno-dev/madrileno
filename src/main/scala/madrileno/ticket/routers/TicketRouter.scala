package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.ticket.domain.TicketId
import madrileno.ticket.routers.dto.{PayTicketRequest, TicketDto}
import madrileno.ticket.services.{PayTicketCommand, PayTicketResult, TicketService}
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class TicketRouter(ticketService: TicketService)(using TelemetryContext) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    (get & path("tickets" / JavaUUID.as[TicketId]) & pathEndOrSingleSlash) { id =>
      complete {
        ticketService.find(id, authContext.userId).map[ToResponseMarshallable] {
          case Some(ticket) => Ok -> TicketDto(ticket)
          case None         => error(NotFound, "ticket-not-found", "Ticket not found")
        }
      }
    } ~
      (post & path("tickets" / JavaUUID.as[TicketId] / "pay") & pathEndOrSingleSlash & entity(as[PayTicketRequest])) { (id, request) =>
        complete {
          ticketService.payTicket(PayTicketCommand(id, authContext.userId, request.chargeId)).map[ToResponseMarshallable] {
            case PayTicketResult.TicketPaid(ticket) => Ok -> TicketDto(ticket)
            case PayTicketResult.TicketNotFound     => error(NotFound, "ticket-not-found", "Ticket not found")
            // 409: could change (e.g. a retry racing the first payment); 410: the ticket will never be payable again.
            case PayTicketResult.TicketAlreadyPaid => error(Conflict, "ticket-already-paid", "Ticket has already been paid")
            case PayTicketResult.TicketCancelled   => error(Gone, "ticket-cancelled", "Ticket has been cancelled")
            case PayTicketResult.TicketExpired     => error(Gone, "ticket-expired", "Ticket hold has expired")
          }
        }
      }
  }
}
