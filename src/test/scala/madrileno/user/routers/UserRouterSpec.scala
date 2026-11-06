package madrileno.user.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.User
import madrileno.user.routers.dto.UserDto
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

import java.time.Instant

class UserRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private def seedUser(): User = {
    val user = TestData.user()
    val _    = application.transactor
      .inTransaction(application.userRepository.create(user, Instant.now()))
      .unsafeRunSync()
    user
  }

  path("/v1/users/me")(
    supports(
      GET,
      description = "Get the authenticated user's profile",
      summary = "Returns the current user (id, fullName, emailAddress, emailVerified, avatarUrl)",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Users")
    )(
      withSetup(seedUser())
        .request(user => onRequest(security = bearer.apply(validJwt(AuthContext(user)))))
        .respondsWith[UserDto](Ok, description = "The authenticated user")
        .assert { case (ctx, user) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe user.id
          response.body.fullName shouldBe user.fullName
          response.body.emailAddress shouldBe user.emailAddress
          response.body.emailVerified shouldBe user.emailVerified
        },
      withSetup {
        val user = seedUser()
        val _    = application.transactor.inTransaction(application.userRepository.anonymize(user.id, Instant.now())).unsafeRunSync()
        user
      }.request { user => onRequest(security = bearer.apply(validJwt(AuthContext(user)))) }
        .respondsWith[Error[Unit]](Unauthorized, description = "Valid JWT for a deleted account (the residual access-token window)")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("This account no longer exists")
        }
    ),
    supports(
      DELETE,
      description =
        "Delete the authenticated user's account: anonymizes the profile, then asynchronously revokes all refresh tokens (already-issued access tokens stay valid until they expire) and cleans up the user's auctions",
      summary = "Delete own account (idempotent)",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Users")
    )(
      withSetup {
        val user = TestData.user()
        val _    = application.transactor.inTransaction(application.userRepository.create(user, Instant.now())).unsafeRunSync()
        user
      }.request { user => onRequest(security = bearer.apply(validJwt(AuthContext(user)))) }
        .respondsWith[EmptyBody](NoContent, description = "Account deleted (or was already deleted)")
        .assert { case (ctx, user) =>
          val _ = ctx.performRequest(allRoutes)
          application.transactor.inSession(application.userRepository.find(user.id)).unsafeRunSync() shouldBe None
        }
    )
  )
}
