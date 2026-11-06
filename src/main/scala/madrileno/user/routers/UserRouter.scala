package madrileno.user.routers

import madrileno.auth.domain.AuthContext
import madrileno.user.routers.dto.UserDto
import madrileno.user.services.UserService
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class UserRouter(userService: UserService)(using TelemetryContext) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    (get & path("users" / "me") & pathEndOrSingleSlash) {
      complete {
        userService.getCurrentUser(authContext.userId).map[ToResponseMarshallable] {
          case Some(user) => Ok -> UserDto(user)
          case None       => error(Unauthorized, "account-deleted", "This account no longer exists")
        }
      }
    } ~ (delete & path("users" / "me") & pathEndOrSingleSlash) {
      complete {
        userService.deleteAccount(authContext.userId).map[ToResponseMarshallable](_ => NoContent)
      }
    }
  }
}
