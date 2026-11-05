package madrileno.healthcheck.routers

import madrileno.healthcheck.routers.dto.{DepStatus, HealthCheckDto, ReadinessDto}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.stir.server.Route

class HealthCheckRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  path("/v1/health-check")(
    supports(GET, description = "Liveness probe — does not touch dependencies", summary = "Returns app metadata", tags = Seq("Health Check"))(
      onRequest()
        .respondsWith[HealthCheckDto](Ok, description = "App metadata")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name should not be empty
          response.body.version should not be empty
        }
    )
  )

  path("/v1/health-check/readiness")(
    supports(
      GET,
      description =
        "Readiness probe for load balancers and orchestrators — answers 503 when Postgres does not answer within a second. No authentication.",
      summary = "Reports whether this instance can serve requests",
      tags = Seq("Health Check")
    )(
      onRequest()
        .respondsWith[ReadinessDto](Ok, description = "Postgres answered")
        .assert { ctx =>
          ctx.performRequest(allRoutes).body.status shouldBe DepStatus.Up
        }
    )
  )
}
