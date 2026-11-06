package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.comcast.ip4s.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Header, Method, Request, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.stir.server.{Route, ToHttpRoutes}

import scala.concurrent.duration.DurationInt

class RateLimitDirectivesSpec extends AnyFunSpec with Matchers {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private given IORuntime        = IORuntime.global

  private def routerWith(runtime: RateLimiterRuntime): BaseRouter & RateLimitDirectives =
    new BaseRouter with RateLimitDirectives {
      override protected val rateLimiterRuntime: RateLimiterRuntime = runtime
    }

  private def trusting(proxy: String): RateLimiterRuntime =
    RateLimiterRuntime.scaffeine(trustedProxies = List(Cidr.fromString(proxy).get))

  private def routesFor(runtime: RateLimiterRuntime): Route = {
    val router = routerWith(runtime)
    import router.*
    (get & path("hello") & pathEndOrSingleSlash) {
      rateLimited("test", to = 3, within = 1.minute) {
        complete(router.Ok -> "ok")
      }
    }
  }

  private def hit(
    routes: Route,
    header: Option[(String, String)] = None,
    remote: Option[String] = None
  ) = {
    val base       = Request[IO](Method.GET, Uri.unsafeFromString("/hello"))
    val withHeader = header.fold(base) { case (n, v) => base.putHeaders(Header.Raw(CIString(n), v)) }
    val request    = remote.fold(withHeader) { ip =>
      withHeader.withAttribute(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = SocketAddress(ipv4"127.0.0.1", port"12345"),
          remote = SocketAddress(IpAddress.fromString(ip).get, port"12345"),
          secure = false
        )
      )
    }
    routes.toHttpRoutes.orNotFound.run(request).unsafeRunSync()
  }

  describe("rateLimited directive") {
    it("passes through under the limit and returns 429 once exceeded") {
      val routes = routesFor(RateLimiterRuntime.scaffeine())
      hit(routes).status shouldBe Status.Ok
      hit(routes).status shouldBe Status.Ok
      hit(routes).status shouldBe Status.Ok
      val limited = hit(routes)
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("60")
    }

    it("isolates buckets by discriminator key") {
      val runtime                  = RateLimiterRuntime.scaffeine()
      val withKey: String => Route = clientId => {
        val router = routerWith(runtime)
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("isolated", to = 1, within = 1.minute, by = byHeader("X-Client")) {
            complete(router.Ok -> clientId)
          }
        }
      }

      hit(withKey("a"), Some("X-Client" -> "a")).status shouldBe Status.Ok
      hit(withKey("a"), Some("X-Client" -> "a")).status shouldBe Status.TooManyRequests
      hit(withKey("b"), Some("X-Client" -> "b")).status shouldBe Status.Ok
    }

    it("does not increment the counter for branches that don't match the method/path") {
      val routes: Route = {
        val router = routerWith(RateLimiterRuntime.scaffeine())
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("get-bucket", to = 1, within = 1.minute) {
            complete(router.Ok -> "got")
          }
        } ~
          (post & path("hello") & pathEndOrSingleSlash) {
            rateLimited("post-bucket", to = 1, within = 1.minute) {
              complete(router.Created -> "posted")
            }
          }
      }

      (1 to 100).foreach { _ =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString("/hello"))
        routes.toHttpRoutes.orNotFound.run(req).unsafeRunSync()
      }
      val getResp = routes.toHttpRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString("/hello"))).unsafeRunSync()
      getResp.status shouldBe Status.Ok
    }

    it("the default discriminator ignores X-Forwarded-For when there are no trusted proxies") {
      val routes: Route = {
        val router = routerWith(RateLimiterRuntime.scaffeine())
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("direct", to = 1, within = 1.minute) {
            complete(router.Ok -> "ok")
          }
        }
      }

      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4"), remote = Some("203.0.113.9")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "5.6.7.8"), remote = Some("203.0.113.9")).status shouldBe Status.TooManyRequests
    }

    it("byClientIpForwarded ignores a spoofed X-Forwarded-For when the peer is not a trusted proxy") {
      val routes: Route = {
        val router = routerWith(trusting("10.0.0.0/8"))
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("untrusted", to = 1, within = 1.minute, by = byClientIpForwarded) {
            complete(router.Ok -> "ok")
          }
        }
      }

      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4"), remote = Some("203.0.113.9")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "5.6.7.8"), remote = Some("203.0.113.9")).status shouldBe Status.TooManyRequests
    }

    it("byClientIpForwarded resolves the client through X-Forwarded-For when the peer is a trusted proxy") {
      val routes: Route = {
        val router = routerWith(trusting("10.0.0.0/8"))
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("trusted", to = 1, within = 1.minute, by = byClientIpForwarded) {
            complete(router.Ok -> "ok")
          }
        }
      }

      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4"), remote = Some("10.0.0.1")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "5.6.7.8"), remote = Some("10.0.0.1")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4"), remote = Some("10.0.0.1")).status shouldBe Status.TooManyRequests
    }

    it("byClientIpForwarded falls back to the trusted proxy's socket address when X-Forwarded-For has no valid IP") {
      val routes: Route = {
        val router = routerWith(trusting("10.0.0.0/8"))
        import router.*
        (get & path("hello") & pathEndOrSingleSlash) {
          rateLimited("xff-invalid", to = 2, within = 1.minute, by = byClientIpForwarded) {
            complete(router.Ok -> "ok")
          }
        }
      }

      hit(routes, Some("X-Forwarded-For" -> "not-an-ip"), remote = Some("10.0.0.1")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "still-not-an-ip"), remote = Some("10.0.0.1")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "garbage"), remote = Some("10.0.0.1")).status shouldBe Status.TooManyRequests
    }
  }
}
