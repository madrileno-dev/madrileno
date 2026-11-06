package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.ticket.domain.{EventId, TicketId}
import madrileno.ticket.routers.dto.{CreateEventRequest, EventDto, HoldSeatsRequest, OrderDto, PayTicketRequest, TicketDto}
import madrileno.ticket.services.{
  CreateEventCommand,
  CreateEventResult,
  HoldSeatsCommand,
  HoldSeatsResult,
  PayTicketCommand,
  PayTicketResult,
  TicketService
}
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
      } ~
      (post & path("events") & pathEndOrSingleSlash & entity(as[CreateEventRequest])) { request =>
        complete {
          ticketService.createEvent(CreateEventCommand(request.name, request.startsAt)).map[ToResponseMarshallable] {
            case CreateEventResult.EventCreated(event) => Created -> EventDto(event)
            case CreateEventResult.EventInThePast      => error(UnprocessableContent, "event-in-the-past", "Event must start in the future")
          }
        }
      } ~
      (get & path("events" / JavaUUID.as[EventId]) & pathEndOrSingleSlash) { eventId =>
        complete {
          ticketService.getEvent(eventId).map[ToResponseMarshallable] {
            case Some(event) => Ok -> EventDto(event)
            case None        => error(NotFound, "event-not-found", "Event not found")
          }
        }
      } ~
      (post & path("events" / JavaUUID.as[EventId] / "orders") & pathEndOrSingleSlash & entity(as[HoldSeatsRequest])) { (eventId, request) =>
        complete {
          ticketService.holdSeats(HoldSeatsCommand(eventId, authContext.userId, request.seatIds)).map[ToResponseMarshallable] {
            case HoldSeatsResult.SeatsHeld(order, tickets) => Created -> OrderDto(order, tickets)
            case HoldSeatsResult.EventNotFound             => error(NotFound, "event-not-found", "Event not found")
            // 409: could change (e.g. sales are not open); 410: a cancelled event will never sell again.
            case HoldSeatsResult.EventNotOnSale   => error(Conflict, "event-not-on-sale", "Event is not on sale")
            case HoldSeatsResult.EventCancelled   => error(Gone, "event-cancelled", "Event has been cancelled")
            case HoldSeatsResult.NoSeatsSelected  => error(UnprocessableContent, "no-seats-selected", "At least one seat must be selected")
            case HoldSeatsResult.DuplicateSeats   => error(UnprocessableContent, "duplicate-seats", "Each seat may be selected only once")
            case HoldSeatsResult.SeatNotInEvent   => error(UnprocessableContent, "seat-not-in-event", "A selected seat does not belong to this event")
            case HoldSeatsResult.SeatAlreadyTaken => error(Conflict, "seat-already-taken", "A selected seat has already been taken")
          }
        }
      }
  }
}
