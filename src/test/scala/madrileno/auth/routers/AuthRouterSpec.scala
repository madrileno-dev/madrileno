package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.{AuthContext, Credential, FirebaseJwt, Provider, ProviderUserId, RefreshTokenSecret, UserAgent, UserAuth}
import madrileno.auth.repositories.{RefreshTokenRepository, UserAuthRepository}
import madrileno.auth.routers.dto.{
  AuthWithEmailRequest,
  AuthWithFirebaseRequest,
  AuthWithOidcRequest,
  AuthWithRefreshTokenRequest,
  AuthenticatedResponse,
  LogoutRequest,
  SessionDto
}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.{EmailAddress, UserId}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Request
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

import java.time.Instant
import java.util.UUID

class AuthRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private def seedFirebaseUser(blockedAt: Option[Instant] = None): UserId = {
    val userAuthRepository = new UserAuthRepository()
    val now                = Instant.now()
    application.transactor
      .inTransaction {
        userAuthRepository.findForUpdate(firebaseToken.provider, firebaseToken.providerUserId).flatMap {
          case Some(userAuth) =>
            blockedAt.fold(IO.pure(userAuth.userId)) { ts =>
              application.userRepository.update(userAuth.userId, _.copy(blockedAt = Some(ts)), ts).as(userAuth.userId)
            }
          case None =>
            val userId   = TestData.randomUserId()
            val user     = firebaseToken.toUser(userId).copy(blockedAt = blockedAt)
            val userAuth = UserAuth(TestData.randomUserAuthId(), userId, firebaseToken)
            application.userRepository.create(user, now) *>
              userAuthRepository.save(userAuth, now).as(userId)
        }
      }
      .unsafeRunSync()
  }

  private def seedDevUser(email: String): String = {
    val userAuthRepository = new UserAuthRepository()
    val devToken           = TestData.verifiedExternalToken(
      provider = Provider.Dev,
      providerUserId = ProviderUserId(email),
      credential = Credential(email),
      fullName = None,
      emailAddress = Some(EmailAddress(email))
    )
    val now      = Instant.now()
    val userId   = TestData.randomUserId()
    val user     = devToken.toUser(userId)
    val userAuth = UserAuth(TestData.randomUserAuthId(), userId, devToken)
    val _        = application.transactor
      .inTransaction {
        application.userRepository.create(user, now) *> userAuthRepository.save(userAuth, now)
      }
      .unsafeRunSync()
    email
  }

  private def seedRefreshToken(): RefreshTokenSecret = {
    val user   = TestData.user()
    val issued = TestData.issuedRefreshToken(userId = user.id)
    val _      = application.transactor
      .inTransaction {
        application.userRepository.create(user, Instant.now()) *>
          new RefreshTokenRepository().save(issued.token)
      }
      .unsafeRunSync()
    issued.secret
  }

  path("/v1/auth/firebase")(
    supports(
      POST,
      description = "Authenticate with Firebase JWT token",
      summary = "Exchange Firebase token for internal JWT and refresh token",
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; a new user account was created (userCreated = true)")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.unwrap should have length 43
          response.body.userCreated shouldBe true
        },
      withSetup {
        seedFirebaseUser()
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token"))))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; existing user (userCreated = false)")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.unwrap should have length 43
          response.body.userCreated shouldBe false
        },
      withSetup {
        seedFirebaseUser(blockedAt = Some(Instant.now()))
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token"))))
        .respondsWith[Error[Unit]](Locked, description = "User is blocked")
        .assert { case (ctx, userId) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("User is blocked")

          // Unblock the user so subsequent tests aren't poisoned
          application.transactor
            .inTransaction {
              application.userRepository.update(userId, _.copy(blockedAt = None), Instant.now())
            }
            .unsafeRunSync()
        },
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("rejected-external-token")))
        .respondsWith[Error[Unit]](Unauthorized, description = "Invalid Firebase token")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid Firebase token")
        }
    )
  )

  path("/v1/auth/refresh-token")(
    supports(
      POST,
      description = "Authenticate with a refresh token",
      summary = "Exchange refresh token for a new JWT and refresh token",
      tags = Seq("Auth")
    )(
      withSetup(seedRefreshToken())
        .request(secret => onRequest(body = AuthWithRefreshTokenRequest(secret)))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated with refresh token")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.unwrap should have length 43
          response.body.userCreated shouldBe false
          response.headers.find(_.name.equalsIgnoreCase("Cache-Control")).map(_.value) shouldBe Some("no-store")
        },
      withSetup {
        val secret  = seedRefreshToken()
        val rotated = allRoutes.orNotFound
          .run(Request[IO](POST, uri"/v1/auth/refresh-token").withEntity(AuthWithRefreshTokenRequest(secret)))
          .unsafeRunSync()
        val successor = rotated.as[AuthenticatedResponse].unsafeRunSync().refreshToken
        (secret, successor)
      }.request { case (secret, _) => onRequest(body = AuthWithRefreshTokenRequest(secret)) }
        .respondsWith[AuthenticatedResponse](
          Ok,
          description =
            "Replay within the reuse grace window, i.e. a retry after a lost rotation response: the same successor is delivered again with a fresh JWT"
        )
        .assert { case (ctx, (_, successor)) =>
          val response = ctx.performRequest(allRoutes)
          response.body.refreshToken shouldBe successor
          response.body.jwt.toString should not be empty
        },
      onRequest(body = AuthWithRefreshTokenRequest(TestData.refreshTokenSecret()))
        .respondsWith[Error[Unit]](
          Unauthorized,
          description = "Unknown, revoked, or expired refresh token, or a replay outside the reuse grace window"
        )
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid refresh token")
        },
      onRequest(body = AuthWithRefreshTokenRequest(RefreshTokenSecret(TestData.randomUuid().toString)))
        .respondsWith[Error[Unit]](
          Unauthorized,
          description = "The token shape is not validated: a pre-rotation UUID or any other string is simply an unknown token, not a bad request"
        )
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid refresh token")
        }
    )
  )

  path("/v1/auth/logout")(
    supports(
      POST,
      description =
        "Log out: revokes the session (refresh-token family) the given refresh token belongs to, including any successor a concurrent rotation minted. The refresh token is the credential, so this works after the JWT expired. Unknown, used, expired and already revoked tokens answer 204 as well, so clients can call it fire-and-forget and clear local state either way.",
      summary = "Revoke the session behind a refresh token",
      tags = Seq("Auth")
    )(
      withSetup(seedRefreshToken())
        .request(secret => onRequest(body = LogoutRequest(secret)))
        .respondsWith[EmptyBody](NoContent, description = "Session revoked; the token no longer refreshes")
        .assert { case (ctx, secret) =>
          val _       = ctx.performRequest(allRoutes)
          val refresh = allRoutes.orNotFound
            .run(Request[IO](POST, uri"/v1/auth/refresh-token").withEntity(AuthWithRefreshTokenRequest(secret)))
            .unsafeRunSync()
          refresh.status shouldBe Unauthorized
        },
      onRequest(body = LogoutRequest(TestData.refreshTokenSecret()))
        .respondsWith[EmptyBody](NoContent, description = "Unknown token; nothing to revoke")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/v1/auth/dev")(
    supports(
      POST,
      description =
        "Dev-mode login: exchanges a bare email address for an internal JWT and refresh token, creating the user on first login. Gated by `DEV_AUTH_ENABLED` (`dev-auth.enabled`, off by default) — when disabled the endpoint answers 404. Never enable outside local/dev environments.",
      summary = "Dev-only: authenticate with an email address (no password)",
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithEmailRequest(s"dev-${TestData.randomUuid()}@example.com"))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; a new user account was created (userCreated = true)")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.unwrap should have length 43
          response.body.userCreated shouldBe true
        },
      withSetup {
        seedDevUser(s"dev-existing-${TestData.randomUuid()}@example.com")
      }.request(email => onRequest(body = AuthWithEmailRequest(email)))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; existing user (userCreated = false)")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.userCreated shouldBe false
        },
      onRequest(body = AuthWithEmailRequest("not-an-email"))
        .respondsWith[Error[Unit]](Unauthorized, description = "The supplied value is not an email address")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("dev auth requires an email address")
        }
    )
  )

  path("/v1/auth/oidc/{provider}")(
    supports(
      POST,
      description =
        "Authenticate with an OIDC ID token. The frontend completes the Authorization Code + PKCE flow against the provider; the backend verifies the resulting `id_token` against the provider's JWKS and issues an internal JWT + refresh token. The `{provider}` segment is the name configured under `oidc.providers` (or via `OIDC_PROVIDER_NAME`).",
      summary = "Exchange an OIDC ID token for an internal JWT and refresh token",
      pathParameters = p[String]("provider"),
      tags = Seq("Auth")
    )(
      onRequest(pathParameters = "test-oidc", body = AuthWithOidcRequest("test-token"))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; a new user account was created (userCreated = true)")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.unwrap should have length 43
          response.body.userCreated shouldBe true
        },
      onRequest(pathParameters = "unknown-provider", body = AuthWithOidcRequest("any-token"))
        .respondsWith[Error[Unit]](NotFound, description = "Provider is not configured")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("No auth provider 'unknown-provider'")
        },
      onRequest(pathParameters = "test-oidc", body = AuthWithOidcRequest("rejected-external-token"))
        .respondsWith[Error[Unit]](Unauthorized, description = "Invalid ID token")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid ID token")
        }
    )
  )

  path("/v1/auth/sessions")(
    supports(
      GET,
      description =
        "List active sessions. A session is a refresh-token family: one login and every rotation descended from it. `id` is the family id and stays stable across rotations; `createdAt` is the login time, `refreshedAt` the last rotation, `expiresAt` when the live token lapses if never refreshed.",
      summary = "Returns active sessions for the authenticated user",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Auth")
    )(
      withSetup {
        val user  = TestData.user()
        val token = TestData.refreshToken(
          userId = user.id,
          userAgent = UserAgent("Firefox/142"),
          familyCreatedAt = Instant.parse("2026-05-01T10:00:00Z"),
          createdAt = Instant.parse("2026-05-02T10:00:00Z")
        )
        val _ = application.transactor
          .inTransaction(application.userRepository.create(user, Instant.now()) *> new RefreshTokenRepository().save(token))
          .unsafeRunSync()
        (user, token)
      }.request { case (user, _) => onRequest(security = bearer.apply(validJwt(AuthContext(user)))) }
        .respondsWith[List[SessionDto]](Ok, description = "Active sessions (families with a live refresh token) for the authenticated user")
        .assert { case (ctx, (_, token)) =>
          val response = ctx.performRequest(allRoutes)
          response.body.map(_.id) shouldBe List(token.familyId)
          response.body.map(_.userAgent) shouldBe List(UserAgent("Firefox/142"))
          response.body.map(_.createdAt) shouldBe List(Instant.parse("2026-05-01T10:00:00Z"))
          response.body.map(_.refreshedAt) shouldBe List(Instant.parse("2026-05-02T10:00:00Z"))
        }
    ),
    supports(
      DELETE,
      description = "Revoke every session (refresh-token family) of the authenticated user whose live token carries the given user agent",
      summary = "Revoke all sessions for a given user agent",
      securitySchemes = Seq(bearerScheme),
      queryParameters = q[UserAgent]("user-agent"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), queryParameters = UserAgent("test-agent"))
        .respondsWith[EmptyBody](NoContent, description = "Sessions revoked")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )

  path("/v1/auth/sessions/{sessionId}")(
    supports(
      DELETE,
      description = "Revoke a specific session: the whole refresh-token family behind the given id, including any rotation that lands concurrently",
      summary = "Revoke a session by its id",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[UUID]("sessionId"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), pathParameters = TestData.randomUuid())
        .respondsWith[EmptyBody](NoContent, description = "Session revoked")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )
}
