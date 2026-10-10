package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.FirebaseJwt
import madrileno.auth.routers.dto.AuthWithFirebaseRequest
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.{Error, RateLimiterRuntime}
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.{EntityEncoder, MediaType, Request, Status, Uri}
import org.typelevel.ci.CIString
import pl.iterators.stir.server.Route

class AuthRateLimitSpec extends BaseRouteSpec with TestApplicationLoader {

  override protected def rateLimiterRuntime: RateLimiterRuntime = RateLimiterRuntime.scaffeine()

  override def route: Route = application.routes(wsb)

  private def post(path: String, body: String) = {
    val request = Request[IO](POST, Uri.unsafeFromString(path))
      .withEntity(body)(using EntityEncoder.stringEncoder)
      .withContentType(`Content-Type`(MediaType.application.json))
    allRoutes.orNotFound.run(request).unsafeRunSync()
  }

  describe("auth endpoint rate limiting") {
    it("returns 429 with Retry-After once the per-client limit for POST /auth/dev (10/min) is exceeded") {
      def postDev() = post("/v1/auth/dev", """{"email":"throttle@example.com"}""")
      (1 to 10).foreach(_ => postDev().status shouldBe Status.Ok)
      val limited = postDev()
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("60")
    }

    it("returns 429 once the per-client limit for POST /auth/firebase (10/min) is exceeded") {
      def postFirebase() = post("/v1/auth/firebase", "{}")
      (1 to 10).foreach(_ => postFirebase().status should not be Status.TooManyRequests)
      postFirebase().status shouldBe Status.TooManyRequests
    }

    it("returns 429 once the per-client limit for POST /auth/oidc/{provider} (10/min) is exceeded") {
      def postOidc() = post("/v1/auth/oidc/test-oidc", "{}")
      (1 to 10).foreach(_ => postOidc().status should not be Status.TooManyRequests)
      postOidc().status shouldBe Status.TooManyRequests
    }

    it("returns 429 once the per-client limit for POST /auth/logout (30/min) is exceeded") {
      def postLogout() = post("/v1/auth/logout", "{}")
      (1 to 30).foreach(_ => postLogout().status should not be Status.TooManyRequests)
      postLogout().status shouldBe Status.TooManyRequests
    }

    it("returns 429 once the per-client limit for POST /auth/refresh-token (30/min) is exceeded") {
      def postRefresh() = post("/v1/auth/refresh-token", "{}")
      (1 to 30).foreach(_ => postRefresh().status should not be Status.TooManyRequests)
      postRefresh().status shouldBe Status.TooManyRequests
    }
  }

  path("/v1/auth/firebase")(
    supports(
      POST,
      description = "Authenticate with Firebase JWT token",
      summary = "Exchange Firebase token for internal JWT and refresh token",
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
        .respondsWith[Error[Unit]](TooManyRequests, description = "Per-client limit exceeded; Retry-After carries the seconds to wait")
        .assert { ctx =>
          (1 to 10).foreach(_ => post("/v1/auth/firebase", "{}"))
          val response = ctx.performRequest(allRoutes)
          response.body.`type`.map(_.toString) shouldBe Some("result:rate-limited")
          response.headers.find(_.name.equalsIgnoreCase("Retry-After")).map(_.value) shouldBe Some("60")
        }
    )
  )
}
