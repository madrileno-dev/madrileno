package madrileno.ticket.routers

import cats.effect.IO
import io.circe.syntax.EncoderOps
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, SectionSpec, SeededEvent, TestApplicationLoader, TestData, TicketSeed}
import madrileno.ticket.domain.{ChargeId, EventId, EventSeat, EventStatus, Order, SeatId, SeatLabel, Ticket, TicketId, TicketStatus}
import madrileno.ticket.routers.dto.{HoldSeatsRequest, OrderDto, PayTicketRequest, TicketDto}
import madrileno.user.domain.User
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Query, Session}

import java.time.{Duration, Instant}
import java.util.UUID

class TicketRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  // A ticket together with its owner's credentials, so a scenario can act as the owner or as somebody else.
  private final case class SeededTicket(ticket: Ticket, owner: User) {
    def ownerAuth: AuthContext = AuthContext(owner)
  }

  // Someone who owns none of the seeded tickets.
  private val stranger: AuthContext = AuthContext(TestData.user())

  private val chargeId = ChargeId("ch_123")

  // Each call seeds its own customer, event and seat, so scenarios never compete for the same live seat.
  // `shape` turns the fresh hold into the state under test (paid, cancelled, expired...) before it is saved.
  private def seedTicket(shape: Ticket => Ticket = identity): SeededTicket = {
    val customer = TestData.user()
    val ticket   = application.transactor
      .inSession {
        for {
          _    <- application.userRepository.create(customer, Instant.now())
          seat <- TicketSeed.seat(customer.id)
          ticket = shape(
                     Ticket.hold(
                       TestData.randomTicketId(),
                       seat.orderId,
                       seat.seatId,
                       customer.id,
                       seat.seatLabel,
                       seat.price,
                       Instant.now(),
                       Duration.ofMinutes(15)
                     )
                   )
          _ <- application.ticketRepository.save(ticket)
        } yield ticket
      }
      .unsafeRunSync()
    SeededTicket(ticket, customer)
  }

  private def paid(ticket: Ticket): Ticket =
    ticket.copy(status = TicketStatus.Paid, chargeId = Some(ChargeId("ch_earlier")), paidAt = Some(Instant.now()))

  private def cancelled(ticket: Ticket): Ticket =
    ticket.copy(status = TicketStatus.Cancelled)

  // The hold ran out and nothing cancelled it yet (the expiry task has not run): still Held, heldUntil in the past.
  private def expired(ticket: Ticket): Ticket =
    ticket.copy(heldUntil = Instant.now().minusSeconds(60))

  path("/v1/tickets/{id}")(
    supports(
      GET,
      description = "Get a ticket by id",
      summary = "Returns the caller's own ticket; someone else's ticket is reported as not found",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[TicketId]("id"),
      tags = Seq("Tickets")
    )(
      withSetup(seedTicket())
        .request(seeded => onRequest(security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id))
        .respondsWith[TicketDto](Ok, description = "Ticket found")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe seeded.ticket.id
          response.body.seatLabel shouldBe seeded.ticket.seatLabel
          response.body.status shouldBe TicketStatus.Held
        },
      onRequest(security = bearer.apply(validJwt(stranger)), pathParameters = TestData.randomTicketId())
        .respondsWith[Error[Unit]](NotFound, description = "Ticket not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket not found")
        },
      // Authorization: a foreign ticket must look exactly like a missing one.
      withSetup(seedTicket())
        .request(seeded => onRequest(security = bearer.apply(validJwt(stranger)), pathParameters = seeded.ticket.id))
        .respondsWith[Error[Unit]](NotFound, description = "Ticket belongs to someone else: reported as not found")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket not found")
        },
      onRequest(pathParameters = TestData.randomTicketId())
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/v1/tickets/{id}/pay")(
    supports(
      POST,
      description = "Pay for a held ticket",
      summary = "Marks the caller's held ticket as paid with the given payment-provider charge",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[TicketId]("id"),
      tags = Seq("Tickets")
    )(
      withSetup(seedTicket())
        .request(seeded =>
          onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[TicketDto](Ok, description = "Ticket paid")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe seeded.ticket.id
          response.body.status shouldBe TicketStatus.Paid
          // The charge id is a payment-provider reference and must not leak into the customer-facing payload.
          response.body.asJson.noSpaces should not include chargeId.unwrap
        },
      onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(stranger)), pathParameters = TestData.randomTicketId())
        .respondsWith[Error[Unit]](NotFound, description = "Ticket not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket not found")
        },
      // Authorization: paying for someone else's ticket looks exactly like paying for a missing one,
      // and must not change the ticket.
      withSetup(seedTicket())
        .request(seeded =>
          onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(stranger)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[Error[Unit]](NotFound, description = "Ticket belongs to someone else: reported as not found")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket not found")
          val stored = application.transactor.inSession(application.ticketRepository.find(seeded.ticket.id)).unsafeRunSync()
          stored.map(_.status) shouldBe Some(TicketStatus.Held)
        },
      withSetup(seedTicket(paid))
        .request(seeded =>
          onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[Error[Unit]](Conflict, description = "Ticket has already been paid")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket has already been paid")
        },
      withSetup(seedTicket(cancelled))
        .request(seeded =>
          onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[Error[Unit]](Gone, description = "Ticket has been cancelled")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket has been cancelled")
        },
      withSetup(seedTicket(expired))
        .request(seeded =>
          onRequest(body = PayTicketRequest(chargeId), security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[Error[Unit]](Gone, description = "Ticket hold has expired")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket hold has expired")
        },
      // A blank chargeId fails ChargeId validation while decoding the body, before the handler runs.
      withSetup(seedTicket())
        .request(seeded =>
          onRequest(body = BlankChargeRequest(" "), security = bearer.apply(validJwt(seeded.ownerAuth)), pathParameters = seeded.ticket.id)
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Blank charge id")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      onRequest(body = PayTicketRequest(chargeId), pathParameters = TestData.randomTicketId())
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  // An event with seats A-1..A-3 and a customer who exists in the database (orders reference their customer).
  private final case class SeededSale(event: SeededEvent, customer: User) {
    def auth: AuthContext              = AuthContext(customer)
    def seat(label: String): EventSeat = event.seat(label)
  }

  private val seatPrice = TestData.ticketPrice(12_000L)

  private def seedSale(status: EventStatus = EventStatus.OnSale, startsAt: Instant = Instant.now().plusSeconds(86400)): SeededSale = {
    val customer = TestData.user()
    val event    = application.transactor
      .inSession {
        for {
          _     <- application.userRepository.create(customer, Instant.now())
          event <- TicketSeed.event(List(SectionSpec("Parter", seatPrice, List("A-1", "A-2", "A-3").map(SeatLabel(_)))), status, startsAt)
        } yield event
      }
      .unsafeRunSync()
    SeededSale(event, customer)
  }

  // Gives `seat` a live (or, with `shape`, otherwise shaped) ticket owned by a different customer.
  private def takeSeat(
    sale: SeededSale,
    seat: EventSeat,
    shape: Ticket => Ticket = identity
  ): Unit = {
    val other = TestData.user()
    application.transactor
      .inSession {
        val order = Order(TestData.randomOrderId(), sale.event.eventId, other.id, Instant.now())
        val held  =
          shape(Ticket.hold(TestData.randomTicketId(), order.id, seat.id, other.id, seat.label, seatPrice, Instant.now(), Duration.ofMinutes(15)))
        application.userRepository.create(other, Instant.now()) *> application.orderRepository.save(order) *> application.ticketRepository.save(held)
      }
      .unsafeRunSync()
  }

  path("/v1/events/{eventId}/orders")(
    supports(
      POST,
      description = "Hold seats",
      summary = "Creates an order with a Held ticket for each selected seat",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[EventId]("eventId"),
      tags = Seq("Tickets")
    )(
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-2").id, sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[OrderDto](Created, description = "Seats held")
        .assert { case (ctx, sale) =>
          val response = ctx.performRequest(allRoutes)
          response.body.eventId shouldBe sale.event.eventId
          response.body.tickets.map(_.seatLabel) shouldBe List(SeatLabel("A-2"), SeatLabel("A-1"))
          response.body.tickets.map(_.status).distinct shouldBe List(TicketStatus.Held)
          response.body.tickets.map(_.orderId).distinct shouldBe List(response.body.id)
          response.body.tickets.map(_.price.amountMinor).distinct shouldBe List(seatPrice.amountMinor)
          // Tickets belong to the caller: they can fetch them through the ticket endpoint's ownership rule.
          val stored = application.transactor.inSession(application.ticketRepository.find(response.body.tickets.head.id)).unsafeRunSync()
          stored.map(_.customerId) shouldBe Some(sale.customer.id)
        },
      // A seat whose earlier ticket was cancelled is free again.
      withSetup { val sale = seedSale(); takeSeat(sale, sale.seat("A-1"), _.copy(status = TicketStatus.Cancelled)); sale }
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[OrderDto](Created, description = "Seat of a cancelled ticket held again")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.tickets.map(_.seatLabel) shouldBe List(SeatLabel("A-1"))
        },
      onRequest(
        body = HoldSeatsRequest(List(TestData.randomSeatId())),
        security = bearer.apply(validJwt(stranger)),
        pathParameters = TestData.randomEventId()
      )
        .respondsWith[Error[Unit]](NotFound, description = "Event not found")
        .assert { ctx =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event not found")
        },
      withSetup(seedSale(startsAt = Instant.now().minusSeconds(60)))
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](Conflict, description = "Event is not on sale")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event is not on sale")
        },
      withSetup(seedSale(status = EventStatus.Cancelled))
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](Gone, description = "Event has been cancelled")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event has been cancelled")
        },
      withSetup(seedSale())
        .request(sale => onRequest(body = HoldSeatsRequest(Nil), security = bearer.apply(validJwt(sale.auth)), pathParameters = sale.event.eventId))
        .respondsWith[Error[Unit]](UnprocessableContent, description = "No seats selected")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("At least one seat must be selected")
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-1").id, sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Seat selected twice")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Each seat may be selected only once")
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-1").id, TestData.randomSeatId())),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Seat does not belong to the event")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("A selected seat does not belong to this event")
        },
      // Another customer already holds A-1: the whole request fails and A-2 is not held either.
      withSetup { val sale = seedSale(); takeSeat(sale, sale.seat("A-1")); sale }
        .request(sale =>
          onRequest(
            body = HoldSeatsRequest(List(sale.seat("A-2").id, sale.seat("A-1").id)),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](Conflict, description = "Seat already taken")
        .assert { case (ctx, sale) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("A selected seat has already been taken")
          val orders = application.transactor
            .inSession(summon[Session[IO]].unique(ordersOfCustomer)(sale.customer.id.unwrap))
            .unsafeRunSync()
          // The rejected order left nothing behind.
          orders shouldBe 0L
        },
      // A body that does not decode (a seat id that is not a UUID) is rejected before the handler runs.
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = MalformedHoldRequest(List("not-a-uuid")),
            security = bearer.apply(validJwt(sale.auth)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Malformed body")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      onRequest(body = HoldSeatsRequest(List(TestData.randomSeatId())), pathParameters = TestData.randomEventId())
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  private val ordersOfCustomer: Query[UUID, Long] =
    sql"SELECT count(*) FROM ticket_order WHERE customer_id = $uuid".query(int8)

  private final case class MalformedHoldRequest(seatIds: List[String]) derives Encoder.AsObject

  // Same wire shape as PayTicketRequest, but with a plain String so a test can send a value ChargeId rejects.
  private final case class BlankChargeRequest(chargeId: String) derives Encoder.AsObject
}
