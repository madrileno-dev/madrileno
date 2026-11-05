package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.ticket.domain.TicketId
import madrileno.ticket.routers.dto.TicketDto
import madrileno.ticket.services.TicketService
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

// SCAFFOLD-TODO: this is a stub exposing only `GET /tickets/{id}`. Add the
// endpoints your aggregate actually needs (POST to create, GET list with filters, PATCH
// for state transitions, etc.) and thread `authContext.userId` through to the service
// when you have ownership/authorization rules. Delete this comment when you do.
class TicketRouter(ticketService: TicketService)(using TelemetryContext) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    val _ = authContext
    (get & path("tickets" / JavaUUID.as[TicketId]) & pathEndOrSingleSlash) { id =>
      complete {
        ticketService.find(id).map[ToResponseMarshallable] {
          case Some(e) => Ok -> TicketDto(e)
          case None    => error(NotFound, "ticket-not-found", "Ticket not found")
        }
      }
    }
  }
}
