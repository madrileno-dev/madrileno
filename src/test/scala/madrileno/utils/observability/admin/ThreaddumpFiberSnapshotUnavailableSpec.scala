package madrileno.utils.observability.admin

import cats.effect.{FiberSnapshot, IO}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{HttpBasic, SecurityScheme}
import pl.iterators.stir.server.Route

class ThreaddumpFiberSnapshotUnavailableSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  override protected def takeFiberSnapshot: IO[FiberSnapshot] =
    IO.raiseError(new NullPointerException("""Cannot invoke "cats.effect.unsafe.WeakBag.size()" because "this.fiberBag" is null"""))

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  path("/admin/threaddump")(
    supports(
      GET,
      description = "Returns a snapshot of the JVM thread state (Spring-Actuator shape) alongside the cats-effect live fiber snapshot. " +
        "Use this for 'what is this process stuck doing?' debugging. `jvmThreads` exposes non-fiber threads (Netty I/O, Skunk pool, scheduler, finalizer); " +
        "`fibers.workers` shows fibers queued on each compute worker, `fibers.external` shows suspended fibers (Deferred.get, Sleep, etc.). " +
        "Fiber traces depend on `-Dcats.effect.tracing.mode` — default `cached` populates them with the captured async-boundary frames.",
      summary = "Inspect JVM threads + cats-effect fibers",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[Error[Unit]](
          ServiceUnavailable,
          description = "The fiber snapshot lost the race with a blocking handoff on every attempt; retry"
        )
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.`type`.map(_.toString) shouldBe Some("result:fiber-snapshot-unavailable")
        }
    )
  )
}
