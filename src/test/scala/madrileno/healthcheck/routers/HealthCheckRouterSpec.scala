package madrileno.healthcheck.routers

import cats.effect.IO
import cats.syntax.all.*
import madrileno.healthcheck.routers.dto.{DepStatus, HealthCheckDto, ReadinessDto}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.{Request, Status, Uri}
import pl.iterators.stir.server.Route

import scala.concurrent.duration.*

class HealthCheckRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private def readinessBecomes(status: Status): IO[Unit] =
    (IO.sleep(100.millis) *> allRoutes.orNotFound.run(Request[IO](uri = Uri.unsafeFromString("/v1/health-check/readiness"))).map(_.status))
      .iterateUntil(_ == status)
      .timeout(10.seconds)
      .void

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
        "Readiness probe for load balancers and orchestrators — answers 503 when the last check of Postgres, run every couple of seconds in the background, failed or is too old. No authentication.",
      summary = "Reports whether this instance can serve requests",
      tags = Seq("Health Check")
    )(
      onRequest()
        .respondsWith[ReadinessDto](ServiceUnavailable, description = "Postgres did not answer, or no check has finished yet")
        .assert { ctx =>
          readinessBecomes(ServiceUnavailable).unsafeRunSync()
          ctx.performRequest(allRoutes).body.status shouldBe DepStatus.Down
        },
      onRequest()
        .respondsWith[ReadinessDto](Ok, description = "Postgres answered")
        .assert { ctx =>
          application.lifecycles.sequence_
            .surround(readinessBecomes(Ok) *> IO(ctx.performRequest(allRoutes).body.status))
            .unsafeRunSync() shouldBe DepStatus.Up
        }
    )
  )
}
