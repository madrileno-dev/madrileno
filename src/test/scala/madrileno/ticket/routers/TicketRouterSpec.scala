package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.ticket.domain.{Ticket, TicketId, TicketName}
import madrileno.ticket.routers.dto.TicketDto
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.Instant

class TicketRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private val authContext: AuthContext = AuthContext(TestData.user())

  private def seedTicket(): Ticket = {
    val entity = Ticket.create(TestData.randomTicketId(), TicketName("test-name"), Instant.now())
    application.transactor
      .inSession(application.ticketRepository.save(entity))
      .unsafeRunSync()
    entity
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
          response.body.name shouldBe entity.name
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
