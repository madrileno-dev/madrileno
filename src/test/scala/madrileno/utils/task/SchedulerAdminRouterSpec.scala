package madrileno.utils.task

import cats.effect.IO
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Method, Request, Response, Status, Uri}
import pl.iterators.stir.server.Route

class SchedulerAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val jobsUri = Uri.unsafeFromString("/admin/jobs.html")

  private val admin = Authorization(BasicCredentials("admin", "admin"))

  private def jobs(path: String): Response[IO] =
    allRoutes.orNotFound.run(Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path), headers = Headers(admin))).unsafeRunSync()

  describe("/admin/jobs") {
    it("returns the jobs page with the registered recurring task at /admin/jobs.html") {
      val response = jobs("/admin/jobs.html")
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

    it("returns the same report as JSON at /admin/jobs.json") {
      val response = jobs("/admin/jobs.json")
      response.status shouldBe Status.Ok
      response.contentType.map(_.mediaType.subType) shouldBe Some("json")
      val report = response.as[JobsReport].unsafeRunSync()
      report.registered.oneTime should contain("send-mail")
    }

    it("calls a recurring task Missing and the jobs Unhealthy while no scheduler has registered it, in both representations") {
      val report = jobs("/admin/jobs.json").as[JobsReport].unsafeRunSync()
      report.recurring.find(_.name == "cleanup-expired-refresh-tokens").map(_.health) shouldBe Some(JobHealth.Missing)
      report.status shouldBe JobsStatus.Unhealthy
      val page = jobs("/admin/jobs.html").bodyText.compile.string.unsafeRunSync()
      page should include("Missing")
      page should include("Unhealthy")
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

    it("serves nothing at the bare /admin/jobs path") {
      List("/admin/jobs", "/admin/jobs/").map(jobs(_).status) shouldBe List(Status.NotFound, Status.NotFound)
    }
  }
}
