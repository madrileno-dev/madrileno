package madrileno.healthcheck.routers

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import madrileno.auth.domain.AuthContext
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

  override protected def testPgConfig(config: PgConfig): PgConfig = config.copy(max = 2, acquireTimeout = 500.millis)

  private def get(path: String, headers: Headers = Headers.empty): IO[(FiniteDuration, Response[IO])] =
    allRoutes.orNotFound.run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path), headers = headers)).timed

  private def json(response: Response[IO]): Json = response.as[Json].unsafeRunSync()

  private val signedIn = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, validJwt(AuthContext(TestData.user())))))

  private val everyConnectionBusy: Resource[IO, Unit] =
    Resource
      .make(for {
        release <- Deferred[IO, Unit]
        taken   <- List.fill(2)(Deferred[IO, Unit]).sequence
        holders <- taken.traverse(took => application.transactor.inSession(took.complete(()) *> release.get).start)
        _       <- taken.traverse_(_.get)
      } yield (release, holders)) { case (release, holders) => release.complete(()) *> holders.traverse_(_.joinWithNever) }
      .void

  private def postgresPaused: Resource[IO, Unit] = {
    val docker      = DockerClientFactory.instance().client()
    val containerId = withContainers(_.containerId)
    Resource.make(IO.blocking(docker.pauseContainerCmd(containerId).exec()).void)(_ =>
      IO.blocking(docker.unpauseContainerCmd(containerId).exec()).void
    )
  }

  describe("While every pooled connection is busy") {
    it("readiness stays 200, since Postgres still answers, while an API call that can't get a connection answers 503 database-unavailable") {
      val (readiness, liveness, api) = everyConnectionBusy
        .surround((get("/v1/health-check/readiness"), get("/v1/health-check"), get("/v1/users/me", signedIn)).tupled)
        .unsafeRunSync()
      readiness._2.status shouldBe Status.Ok
      readiness._1 should be < 1.second
      liveness._2.status shouldBe Status.Ok
      api._2.status shouldBe Status.ServiceUnavailable
      json(api._2).hcursor.get[String]("type") shouldBe Right("error:database-unavailable")
      api._1 should be < 3.seconds
    }
  }

  describe("While Postgres does not respond at all") {
    it("readiness answers 503 within its timeout and liveness stays 200") {
      val (readiness, liveness) = postgresPaused.surround((get("/v1/health-check/readiness"), get("/v1/health-check")).tupled).unsafeRunSync()
      readiness._2.status shouldBe Status.ServiceUnavailable
      readiness._1 should be < 3.seconds
      liveness._2.status shouldBe Status.Ok
    }

    it("readiness recovers once Postgres responds again") {
      val recovered = (IO.sleep(300.millis) *> get("/v1/health-check/readiness").map(_._2.status)).iterateUntil(_ == Status.Ok).timeout(30.seconds)
      recovered.unsafeRunSync() shouldBe Status.Ok
    }
  }
}
