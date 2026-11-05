package madrileno.ticket

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.ticket.repositories.{EventRepository, OrderRepository, TicketRepository}
import madrileno.ticket.routers.TicketRouter
import madrileno.ticket.services.TicketService
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.AuthRouteProvider
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route
import pureconfig.ConfigSource

trait TicketModule extends AuthRouteProvider {
  val config: ConfigSource
  val clock: Clock[IO]
  val transactor: Transactor
  given telemetryContext: TelemetryContext
  lazy val ticketConfig: TicketConfig         = config.at("ticket").loadOrThrow[TicketConfig]
  lazy val ticketRepository: TicketRepository = wire[TicketRepository]
  lazy val eventRepository: EventRepository   = wire[EventRepository]
  lazy val orderRepository: OrderRepository   = wire[OrderRepository]

  private val ticketService = wire[TicketService]
  private val ticketRouter  = wire[TicketRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ ticketRouter.authedRoutes(auth)
  }
}
