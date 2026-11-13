package madrileno.healthcheck.routers

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.services.{PostgresProbe, ReadinessConfig}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.db.transactor.PgConfig
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request, Response, Status, Uri}
import org.testcontainers.DockerClientFactory
import pl.iterators.stir.server.Route

import scala.concurrent.duration.*

class ReadinessOutageSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val readinessConfig = ReadinessConfig(interval = 100.millis)
  private val acquireTimeout  = 500.millis

  override protected def testPgConfig(config: PgConfig): PgConfig = config.copy(max = 2, acquireTimeout = acquireTimeout)

  override protected def testReadinessConfig: ReadinessConfig = readinessConfig

  private def get(path: String, headers: Headers = Headers.empty): IO[(FiniteDuration, Response[IO])] =
    allRoutes.orNotFound.run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path), headers = headers)).timed

  private def json(response: Response[IO]): Json = response.as[Json].unsafeRunSync()

  private val signedIn = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, validJwt(AuthContext(TestData.user())))))

  private val holdingOneConnection: Resource[IO, Unit] =
    Resource.eval(Deferred[IO, Unit]).flatMap { taken =>
      application.transactor.inSession(taken.complete(()) *> IO.never).background.evalMap(_ => taken.get)
    }

  private val everyConnectionBusy: Resource[IO, Unit] = holdingOneConnection >> holdingOneConnection

  private def postgresPaused: Resource[IO, Unit] = {
    val docker      = DockerClientFactory.instance().client()
    val containerId = withContainers(_.containerId)
    Resource.make(IO.blocking(docker.pauseContainerCmd(containerId).exec()).void)(_ =>
      IO.blocking(docker.unpauseContainerCmd(containerId).exec()).void
    )
  }

  private def readinessBecomes(status: Status): IO[Response[IO]] =
    (IO.sleep(100.millis) *> get("/v1/health-check/readiness").map(_._2))
      .iterateUntil(_.status == status)
      .timeout(10.seconds)

  private def withLifecycles[A](test: IO[A]): A =
    application.lifecycles.sequence_.surround(readinessBecomes(Status.Ok) *> test).unsafeRunSync()

  describe("While every pooled connection is busy") {
    it("readiness stays 200, since Postgres still answers, while an API call that can't get a connection answers 503 database-unavailable") {
      val (readiness, liveness, api) = withLifecycles(
        everyConnectionBusy.surround(
          IO.sleep(readinessConfig.validFor + acquireTimeout) *> readinessBecomes(Status.Ok) *>
            (get("/v1/health-check/readiness"), get("/v1/health-check"), get("/v1/users/me", signedIn)).tupled
        )
      )
      readiness._1 should be < 1.second
      liveness._2.status shouldBe Status.Ok
      api._2.status shouldBe Status.ServiceUnavailable
      json(api._2).hcursor.get[String]("type") shouldBe Right("error:database-unavailable")
      api._1 should be < 3.seconds
    }
  }

  describe("While Postgres does not respond at all") {
    it(
      "readiness turns 503 within a few seconds and answers at once while it does, liveness stays 200, and readiness recovers once Postgres responds again"
    ) {
      val (down, readiness, liveness, recovered) = withLifecycles(for {
        (down, readiness, liveness) <-
          postgresPaused.surround(
            (readinessBecomes(Status.ServiceUnavailable).timed.map(_._1), get("/v1/health-check/readiness"), get("/v1/health-check")).tupled
          )
        recovered <- readinessBecomes(Status.Ok).timeout(30.seconds)
      } yield (down, readiness, liveness, recovered))
      down should be < readinessConfig.interval + PostgresProbe.timeout + 2.seconds
      readiness._2.status shouldBe Status.ServiceUnavailable
      readiness._1 should be < 1.second
      liveness._2.status shouldBe Status.Ok
      recovered.status shouldBe Status.Ok
    }
  }
}
