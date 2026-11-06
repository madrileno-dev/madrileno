package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.ticket.domain.{Event, EventId, TicketId}
import madrileno.ticket.routers.dto.{
  AddSectionRequest,
  CreateEventRequest,
  EventDto,
  HoldSeatsRequest,
  OrderDto,
  PayTicketRequest,
  SectionDto,
  TicketDto
}
import madrileno.ticket.services.{
  AddSectionCommand,
  AddSectionResult,
  CancelTicketCommand,
  CancelTicketResult,
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
            case PayTicketResult.ChargeAlreadyUsed => error(Conflict, "charge-already-used", "This charge was already used for another ticket")
          }
        }
      } ~
      (post & path("tickets" / JavaUUID.as[TicketId] / "cancel") & pathEndOrSingleSlash) { id =>
        complete {
          ticketService.cancelTicket(CancelTicketCommand(id, authContext.userId)).map[ToResponseMarshallable] {
            case CancelTicketResult.TicketCancelledOk(ticket) => Ok -> TicketDto(ticket)
            case CancelTicketResult.TicketNotFound            => error(NotFound, "ticket-not-found", "Ticket not found")
            // Paying is final: a paid ticket would need a refund, which is out of scope.
            case CancelTicketResult.TicketAlreadyPaid      => error(Conflict, "ticket-already-paid", "Ticket has already been paid")
            case CancelTicketResult.TicketAlreadyCancelled => error(Gone, "ticket-cancelled", "Ticket has been cancelled")
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
      (post & path("events" / JavaUUID.as[EventId] / "sections") & pathEndOrSingleSlash & entity(as[AddSectionRequest])) { (eventId, request) =>
        complete {
          ticketService.addSection(AddSectionCommand(eventId, request.name, request.price, request.seatLabels)).map[ToResponseMarshallable] {
            case AddSectionResult.SectionAdded(_, section) => Created -> SectionDto(section)
            case AddSectionResult.EventNotFound            => error(NotFound, "event-not-found", "Event not found")
            // 409: could change (e.g. sales are not open); 410: a cancelled event will never sell again. Same as holding seats.
            case AddSectionResult.EventNotOnSale      => error(Conflict, "event-not-on-sale", "Event is not on sale")
            case AddSectionResult.EventCancelled      => error(Gone, "event-cancelled", "Event has been cancelled")
            case AddSectionResult.NoSeats             => error(UnprocessableContent, "no-seats-in-section", "A section needs at least one seat")
            case AddSectionResult.DuplicateSeatLabels =>
              error(UnprocessableContent, "duplicate-seat-labels", "Each seat label may be used only once in a section")
            case AddSectionResult.PriceMustBePositive =>
              error(UnprocessableContent, "price-must-be-positive", "Section price must be greater than zero")
            case AddSectionResult.SectionNameTaken => error(Conflict, "section-name-taken", "The event already has a section with this name")
          }
        }
      } ~
      (post & path("events" / JavaUUID.as[EventId] / "orders") & pathEndOrSingleSlash & entity(as[HoldSeatsRequest])) { (eventId, request) =>
        complete {
          ticketService.holdSeats(HoldSeatsCommand(eventId, authContext.userId, request.seatIds)).map[ToResponseMarshallable] {
            case HoldSeatsResult.SeatsHeld(order, tickets) => Created -> OrderDto(order, tickets)
            case HoldSeatsResult.EventNotFound             => error(NotFound, "event-not-found", "Event not found")
            // 409: could change (e.g. sales are not open); 410: a cancelled event will never sell again.
            case HoldSeatsResult.EventNotOnSale  => error(Conflict, "event-not-on-sale", "Event is not on sale")
            case HoldSeatsResult.EventCancelled  => error(Gone, "event-cancelled", "Event has been cancelled")
            case HoldSeatsResult.NoSeatsSelected => error(UnprocessableContent, "no-seats-selected", "At least one seat must be selected")
            case HoldSeatsResult.TooManySeats    =>
              error(UnprocessableContent, "too-many-seats", s"At most ${Event.MaxSeatsPerOrder} seats can be selected in one order")
            case HoldSeatsResult.DuplicateSeats   => error(UnprocessableContent, "duplicate-seats", "Each seat may be selected only once")
            case HoldSeatsResult.SeatNotInEvent   => error(UnprocessableContent, "seat-not-in-event", "A selected seat does not belong to this event")
            case HoldSeatsResult.SeatAlreadyTaken => error(Conflict, "seat-already-taken", "A selected seat has already been taken")
          }
        }
      }
  }
}
