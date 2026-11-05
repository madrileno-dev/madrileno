package madrileno.utils.task

import cats.effect.IO
import io.circe.Json
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Header, Headers, Method, Request, Response, Status, Uri}
import org.typelevel.ci.*
import pl.iterators.stir.server.Route

class SchedulerAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val jobsUri = Uri.unsafeFromString("/admin/jobs")

  private val admin = Authorization(BasicCredentials("admin", "admin"))

  private def jobs(accept: Option[String]): Response[IO] =
    allRoutes.orNotFound
      .run(Request[IO](method = Method.GET, uri = jobsUri, headers = Headers(admin, accept.map(Header.Raw(ci"Accept", _)).toList)))
      .unsafeRunSync()

  describe("/admin/jobs") {
    it("returns the jobs page with the registered recurring task to a browser") {
      val response = jobs(Some("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
      response.status shouldBe Status.Ok
      response.contentType.exists(_.mediaType.subType == "html") shouldBe true
      val body = response.bodyText.compile.string.unsafeRunSync()
      // scripts:auction-block-start
      body should include("close-expired-auctions")
      // scripts:auction-block-end
      body should include("Recurring tasks")
      body should include("Registered task types")
      body should include("send-mail")
    }

    it("returns the same report as JSON to a client that asks for it, and to one that names no type") {
      List(Some("application/json"), None).foreach { accept =>
        val response = jobs(accept)
        response.status shouldBe Status.Ok
        response.contentType.map(_.mediaType.subType) shouldBe Some("json")
        val report = response.as[JobsReport].unsafeRunSync()
        report.registered.oneTime should contain("send-mail")
      }
    }

    it("calls a recurring task Missing and the jobs Unhealthy while no scheduler has registered it, in both representations") {
      val report = jobs(Some("application/json")).as[JobsReport].unsafeRunSync()
      report.recurring.find(_.name == "cleanup-expired-refresh-tokens").map(_.health) shouldBe Some(JobHealth.Missing)
      report.status shouldBe JobsStatus.Unhealthy
      val page = jobs(Some("text/html")).bodyText.compile.string.unsafeRunSync()
      page should include("Missing")
      page should include("Unhealthy")
    }

    it("reads media type parameters the way the JSON response is checked, so a choice made is a choice served") {
      jobs(Some("application/json;charset=utf-8")).contentType.map(_.mediaType.subType) shouldBe Some("json")
      jobs(Some("application/json;version=2, text/html;q=0.5")).contentType.map(_.mediaType.subType) shouldBe Some("html")
      jobs(Some("application/json;version=2")).status shouldBe Status.NotAcceptable
    }

    it("answers 406 to a client that accepts neither JSON nor HTML") {
      val response = jobs(Some("image/png"))
      response.status shouldBe Status.NotAcceptable
      response.as[Json].unsafeRunSync().hcursor.get[List[String]]("supportedContentTypes") shouldBe Right(List("application/json", "text/html"))
    }

    it("rejects requests without credentials with 401") {
      val request  = Request[IO](method = Method.GET, uri = jobsUri)
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Unauthorized
    }

    it("rejects requests with wrong credentials with 401") {
      val request  = Request[IO](method = Method.GET, uri = jobsUri, headers = Headers(Authorization(BasicCredentials("admin", "nope"))))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Unauthorized
    }

    it("accepts /admin/jobs/ with a trailing slash") {
      val request =
        Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString("/admin/jobs/"),
          headers = Headers(Authorization(BasicCredentials("admin", "admin")))
        )
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Ok
    }
  }
}
