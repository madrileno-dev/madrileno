package madrileno.healthcheck.routers

import madrileno.healthcheck.routers.dto.{AdminHealthCheckDto, DepStatus}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{HttpBasic, SecurityScheme}
import pl.iterators.stir.server.Route

class AdminHealthCheckRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  // Defaults from application.conf — the test loader doesn't override them.
  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  path("/admin/health-check")(
    supports(
      GET,
      description =
        "Admin-only deep health check for people — probes Postgres and SMTP (reported Disabled when MAILER_SENDER=log), returns 503 if any dep is down. Gated by Basic Auth; probes use /v1/health-check/readiness.",
      summary = "Admin dependency check",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[AdminHealthCheckDto](Ok, description = "All deps up")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.status shouldBe DepStatus.Up
          response.body.postgres.status shouldBe DepStatus.Up
          response.body.smtp.status shouldBe DepStatus.Up
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        },
      onRequest(security = basic.apply(adminUser, "nope"))
        .respondsWith[Error[Unit]](Unauthorized, description = "Wrong credentials")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )
}
