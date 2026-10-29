package madrileno.user

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.user.repositories.UserRepository
import madrileno.user.routers.UserRouter
import madrileno.user.services.UserService
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.outbox.Outbox
import madrileno.utils.http.AuthRouteProvider
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

trait UserModule extends AuthRouteProvider {
  given telemetryContext: TelemetryContext
  val clock: Clock[IO]
  val transactor: Transactor
  lazy val userRepository: UserRepository = wire[UserRepository]
  lazy val outbox: Outbox

  private lazy val userService = wire[UserService]
  private lazy val userRouter  = wire[UserRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ userRouter.authedRoutes(auth)
  }
}
