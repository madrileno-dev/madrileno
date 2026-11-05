package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData, TicketSeed}
import madrileno.ticket.domain.{Ticket, TicketId, TicketStatus}
import madrileno.ticket.routers.dto.TicketDto
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.{Duration, Instant}

class TicketRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private val authContext: AuthContext = AuthContext(TestData.user())

  // Each call seeds its own customer, event and seat, so scenarios never compete for the same live seat.
  private def seedTicket(): Ticket = {
    val customer = TestData.user()
    application.transactor
      .inSession {
        for {
          _    <- application.userRepository.create(customer, Instant.now())
          seat <- TicketSeed.seat(customer.id)
          ticket = Ticket.hold(
                     TestData.randomTicketId(),
                     seat.orderId,
                     seat.seatId,
                     customer.id,
                     seat.seatLabel,
                     seat.price,
                     Instant.now(),
                     Duration.ofMinutes(15)
                   )
          _ <- application.ticketRepository.save(ticket)
        } yield ticket
      }
      .unsafeRunSync()
  }

  path("/v1/tickets/{id}")(
    supports(
      GET,
      description = "Get a ticket by id",
      summary = "Returns the ticket with the given id",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[TicketId]("id"),
      tags = Seq("Tickets")
    )(
      withSetup(seedTicket())
        .request(entity => onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = entity.id))
        .respondsWith[TicketDto](Ok, description = "Ticket found")
        .assert { case (ctx, entity) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe entity.id
          response.body.seatLabel shouldBe entity.seatLabel
          response.body.status shouldBe TicketStatus.Held
        },
      onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = TestData.randomTicketId())
        .respondsWith[Error[Unit]](NotFound, description = "Ticket not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Ticket not found")
        }
    )
  )
}
