package madrileno.utils.observability.admin

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.comcast.ip4s.*
import madrileno.utils.http.RateLimiterRuntime
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.stir.server.{Route, ToHttpRoutes}

import java.io.File

class AdminRateLimitSpec extends AnyFunSpec with Matchers {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())
  private given IORuntime        = IORuntime.global

  private def fromClient(req: Request[IO], ip: String): Request[IO] =
    req.withAttribute(
      Request.Keys.ConnectionInfo,
      Request.Connection(
        local = SocketAddress(ipv4"127.0.0.1", port"12345"),
        remote = SocketAddress(IpAddress.fromString(ip).get, port"12345"),
        secure = false
      )
    )

  private def run(routes: Route, req: Request[IO]) =
    routes.toHttpRoutes.orNotFound.run(req).unsafeRunSync()

  describe("admin heapdump rate limiting") {
    it("limits to 3 per window with a single global bucket shared across distinct clients") {
      val routes   = new HeapdumpAdminRouter(RateLimiterRuntime.scaffeine()).routes
      val existing = File.createTempFile("heapdump-rl-", ".hprof")
      existing.deleteOnExit()
      def hit(clientIp: String) =
        run(routes, fromClient(Request[IO](Method.POST, Uri.unsafeFromString(s"/heapdump?path=${existing.getAbsolutePath}")), clientIp))

      hit("1.1.1.1").status shouldBe Status.Conflict
      hit("2.2.2.2").status shouldBe Status.Conflict
      hit("3.3.3.3").status shouldBe Status.Conflict
      val limited = hit("4.4.4.4")
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("300")
    }
  }

  describe("admin threaddump rate limiting") {
    it("limits to 20 per window") {
      val routes = ThreaddumpAdminRouter(IORuntime.global, RateLimiterRuntime.scaffeine()).routes
      def hit()  = run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/threaddump")))

      (1 to 20).foreach(_ => hit().status shouldBe Status.Ok)
      val limited = hit()
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("60")
    }
  }
}
