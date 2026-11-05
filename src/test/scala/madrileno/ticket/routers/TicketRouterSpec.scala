package madrileno.ticket.routers

import io.circe.syntax.EncoderOps
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData, TicketSeed}
import madrileno.ticket.domain.{ChargeId, Ticket, TicketId, TicketStatus}
import madrileno.ticket.routers.dto.{PayTicketRequest, TicketDto}
import madrileno.user.domain.User
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.{Duration, Instant}

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

  // Same wire shape as PayTicketRequest, but with a plain String so a test can send a value ChargeId rejects.
  private final case class BlankChargeRequest(chargeId: String) derives Encoder.AsObject
}
