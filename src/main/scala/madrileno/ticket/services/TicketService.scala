package madrileno.ticket.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.ticket.TicketConfig
import madrileno.ticket.domain.*
import madrileno.ticket.repositories.{EventRepository, OrderRepository, TicketRepository}
import madrileno.user.domain.UserId
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.{DBInTransaction, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.{CronExpression, Schedule, Task}
import skunk.exception.PostgresErrorException

import java.time.Instant

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
  case ChargeAlreadyUsed
}

final case class CreateEventCommand(name: EventName, startsAt: Instant)

enum CreateEventResult {
  case EventCreated(event: Event)
  case EventInThePast
}

final case class HoldSeatsCommand(
  eventId: EventId,
  customerId: UserId,
  seatIds: List[SeatId])

enum HoldSeatsResult {
  case SeatsHeld(order: Order, tickets: List[Ticket])
  case EventNotFound
  case EventNotOnSale
  case EventCancelled
  case NoSeatsSelected
  case TooManySeats
  case DuplicateSeats
  case SeatNotInEvent
  case SeatAlreadyTaken
}

object TicketService {
  private val PostgresUniqueViolation = "23505"
  private val LiveSeatIndex           = "ticket_live_seat_uidx"
  private val ChargeIdIndex           = "ticket_charge_id_uidx"

  // True only for a unique violation (23505) of the named constraint: another constraint or another error class
  // is never translated and must stay an error.
  private def isUniqueViolationOf(
    code: String,
    constraintName: Option[String],
    index: String
  ): Boolean =
    code == PostgresUniqueViolation && constraintName.contains(index)

  // The partial index that allows one live ticket per seat.
  private[services] def isSeatTakenViolation(code: String, constraintName: Option[String]): Boolean =
    isUniqueViolationOf(code, constraintName, LiveSeatIndex)

  // The unique index on charge_id: a payment-provider charge can pay for one ticket only.
  private[services] def isChargeReusedViolation(code: String, constraintName: Option[String]): Boolean =
    isUniqueViolationOf(code, constraintName, ChargeIdIndex)

  // Meant to be attached to the IO returned by `transactor.inTransaction`, i.e. OUTSIDE the transaction: a failed
  // statement aborts the Postgres transaction ("current transaction is aborted"), so nothing can be done inside it
  // after the violation. By the time this runs the transaction has been rolled back, so neither the order nor any of
  // its tickets exist and reporting SeatAlreadyTaken is accurate. Everything else is rethrown untouched.
  private[services] def seatTakenOrRethrow(error: Throwable): IO[HoldSeatsResult] =
    error match {
      case e: PostgresErrorException if isSeatTakenViolation(e.code, e.constraintName) => IO.pure(HoldSeatsResult.SeatAlreadyTaken)
      case other                                                                       => IO.raiseError(other)
    }

  // Same contract as seatTakenOrRethrow, for payTicket: attach it OUTSIDE `transactor.inTransaction` (a failed
  // statement aborts the Postgres transaction, so nothing can be done inside it). The transaction has been rolled
  // back by then, so the ticket stays Held and reporting ChargeAlreadyUsed is accurate.
  private[services] def chargeReusedOrRethrow(error: Throwable): IO[PayTicketResult] =
    error match {
      case e: PostgresErrorException if isChargeReusedViolation(e.code, e.constraintName) => IO.pure(PayTicketResult.ChargeAlreadyUsed)
      case other                                                                          => IO.raiseError(other)
    }

  private[services] def toResult(rejection: HoldRejection): HoldSeatsResult =
    rejection match {
      case HoldRejection.NoSeatsSelected => HoldSeatsResult.NoSeatsSelected
      case HoldRejection.TooManySeats    => HoldSeatsResult.TooManySeats
      case HoldRejection.DuplicateSeats  => HoldSeatsResult.DuplicateSeats
      case HoldRejection.EventNotOnSale  => HoldSeatsResult.EventNotOnSale
      case HoldRejection.EventCancelled  => HoldSeatsResult.EventCancelled
      case HoldRejection.SeatNotInEvent  => HoldSeatsResult.SeatNotInEvent
    }
}

class TicketService(
  ticketRepository: TicketRepository,
  eventRepository: EventRepository,
  orderRepository: OrderRepository,
  ticketConfig: TicketConfig,
  transactor: Transactor
)(using
  Clock[IO],
  UUIDGen[IO],
  TelemetryContext)
    extends LoggingSupport {

  // Ownership is enforced here: a ticket owned by someone else is reported as None (-> 404), exactly like a
  // missing one, so the endpoint cannot be used to probe which ticket ids exist.
  def find(id: TicketId, requesterId: UserId): IO[Option[Ticket]] =
    transactor.inSession(ticketRepository.find(id)).map(_.filter(_.customerId == requesterId))

  // Any authenticated user may create an event: there are no organizer roles in this step (a conscious simplification).
  def createEvent(command: CreateEventCommand): IO[CreateEventResult] =
    for {
      now    <- Clock[IO].realTimeInstant
      id     <- IdGenerator.generateId(EventId)
      result <- Event.create(id, command.name, command.startsAt, now) match {
                  case Left(EventCreationRejection.EventInThePast) => IO.pure(CreateEventResult.EventInThePast)
                  case Right(event) => transactor.inSession(eventRepository.save(event)).as(CreateEventResult.EventCreated(event))
                }
    } yield result

  // Reads only the event itself, without its seating plan.
  def getEvent(id: EventId): IO[Option[Event]] =
    transactor.inSession(eventRepository.find(id))

  def payTicket(command: PayTicketCommand): IO[PayTicketResult] = {
    transactor
      .inTransaction {
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
      .handleErrorWith(TicketService.chargeReusedOrRethrow)
  }

  // Whether a seat is free is NOT checked beforehand (that would be check-then-act and race with a concurrent hold):
  // the insert itself is the check, and the partial unique index ticket_live_seat_uidx rejects the loser.
  // The tickets are INSERTED in a canonical order (by seat id), not in request order, while the returned tickets keep
  // the request order. A unique-index insert waits for a concurrent transaction that inserted the same seat first, so
  // two holds of [A,B] and [B,A] inserting in request order would each wait for the other (Postgres 40P01 deadlock,
  // a 500). With one global order the one that gets the lowest contested seat first wins and the other fails cleanly
  // with a unique violation.
  // DEBT: the order and its tickets are written in one transaction and no event is emitted for other modules
  // (accepted compromise, there is no outbox in this project).
  def holdSeats(command: HoldSeatsCommand): IO[HoldSeatsResult] = {
    transactor
      .inTransaction {
        eventRepository.findWithSeats(command.eventId).flatMap {
          case None        => IO.pure(HoldSeatsResult.EventNotFound)
          case Some(event) =>
            for {
              now     <- Clock[IO].realTimeInstant
              orderId <- IdGenerator.generateId(OrderId)
              // One id per distinct seat; duplicates are rejected by the domain before any id is used.
              ticketIds <- command.seatIds.distinct.traverse(seatId => IdGenerator.generateId(TicketId).map(seatId -> _)).map(_.toMap)
              result    <- event.holdSeats(orderId, ticketIds, command.customerId, command.seatIds, now, ticketConfig.holdDurationJava) match {
                          case Left(rejection)         => IO.pure(TicketService.toResult(rejection))
                          case Right((order, tickets)) =>
                            releaseExpiredHolds(tickets.map(_.seatId), now) *> orderRepository.save(order) *> tickets
                              .sortBy(_.seatId.unwrap)
                              .traverse_(ticketRepository.save(_))
                              .as(HoldSeatsResult.SeatsHeld(order, tickets))
                        }
            } yield result
        }
      }
      .handleErrorWith(TicketService.seatTakenOrRethrow)
  }

  // A Held row keeps its seat blocked in ticket_live_seat_uidx until it is actually cancelled, so a hold that ran
  // out would block its seat until the task below runs. To not answer SeatAlreadyTaken for a seat whose hold is
  // already dead, the holds that expired on the requested seats are cancelled here, in the SAME transaction and
  // before the new tickets are inserted (a rollback therefore restores them too). `update` re-reads each row under
  // FOR UPDATE, so a hold paid or cancelled in the meantime comes back as Left/None and is ignored. The ids are
  // processed in a canonical order so that concurrent holds lock the old tickets in the same order (no deadlock).
  private def releaseExpiredHolds(seatIds: List[SeatId], now: Instant): DBInTransaction[Unit] =
    ticketRepository
      .listExpiredHoldsForSeats(seatIds, now)
      .flatMap(_.sortBy(_.unwrap).traverse_(id => ticketRepository.update(id, _.cancel(now))))

  // Runs once a minute (cron second 0, same idiom as close-expired-auctions). It is a safety net that cleans up
  // every expired hold, including those on seats nobody asks for: holdSeats itself already cancels the expired
  // holds on the seats it is asked to hold, so a requested seat never waits for this task.
  val cancelExpiredHoldsTask: Task[Unit] =
    Task.recurring("cancel-expired-ticket-holds", Schedule.Cron(CronExpression.unsafeParse("0 * * ? * *"))) { _ =>
      cancelExpiredHolds().void
    }

  // Returns how many tickets this run cancelled.
  // The list is only a list of CANDIDATES: between listing and cancelling a ticket can be paid (or cancelled) by a
  // concurrent request. Each cancellation therefore runs in its own transaction, where `update` re-reads the row
  // under FOR UPDATE and the domain re-checks its status. A lost race comes back as Left and is skipped silently,
  // and an unexpected failure of one ticket neither rolls back nor stops the others.
  def cancelExpiredHolds(): IO[Int] = {
    def cancelOne(id: TicketId, now: Instant): IO[Boolean] = {
      transactor
        .inTransaction(ticketRepository.update(id, _.cancel(now)))
        .map {
          case Some(Right(_)) => true
          case Some(Left(_))  => false // paid or cancelled since it was listed
          case None           => false // deleted since it was listed
        }
        .handleErrorWith(error => logger.warn(error)(s"Failed to cancel expired hold $id - skipping").as(false))
    }

    for {
      now        <- Clock[IO].realTimeInstant
      candidates <- transactor.inSession(ticketRepository.listExpiredHolds(now))
      outcomes   <- candidates.traverse(cancelOne(_, now))
      cancelled = outcomes.count(identity)
      _ <- logger.info(s"Cancelled $cancelled expired ticket hold(s) at $now").whenA(cancelled > 0)
    } yield cancelled
  }
}
