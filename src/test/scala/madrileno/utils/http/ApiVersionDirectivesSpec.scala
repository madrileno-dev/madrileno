package madrileno.utils.http

import cats.effect.unsafe.IORuntime
import org.http4s.Status
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.stir.server.Directives.*
import pl.iterators.stir.server.{ExceptionHandler, Route}
import pl.iterators.stir.testkit.{RouteTestTimeout, ScalatestRouteTest}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt

class ApiVersionDirectivesSpec extends AnyFunSpec with Matchers with ScalatestRouteTest {

  override implicit def runtime: IORuntime = IORuntime.global

  given RouteTestTimeout = RouteTestTimeout(5.seconds)

  import ApiVersionDirectives.*

  describe("apiVersionPrefix") {
    val route: Route =
      apiVersionPrefix {
        path("ping") {
          currentApiVersion(v => complete(s"matched=${v.urlSegment}"))
        }
      }

    it("matches and stores a known ApiVersion") {
      Get("/v1/ping") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "matched=v1"
      }
    }

    it("rejects unknown version segments") {
      Get("/vUNKNOWN/ping") ~> route ~> check {
        handled shouldBe false
      }
    }
  }

  describe("currentApiVersion") {
    it("rejects when called without an apiVersionPrefix upstream") {
      val route: Route = path("ping") {
        currentApiVersion(v => complete(v.urlSegment))
      }
      Get("/ping") ~> route ~> check {
        handled shouldBe false
        rejections shouldBe empty
      }
    }

    it("survives intermediate directive composition") {
      val route: Route =
        apiVersionPrefix {
          handleExceptions(ExceptionHandler { case _ => complete(Status.InternalServerError -> "boom") }) {
            path("ping") {
              currentApiVersion(v => complete(v.urlSegment))
            }
          }
        }
      Get("/v1/ping") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "v1"
      }
    }
  }

  describe("apiVersion") {
    val route: Route =
      apiVersionPrefix {
        path("ping") {
          apiVersion(ApiVersion.V1)(complete("v1-branch"))
        }
      }

    it("passes when the matched version equals the target") {
      Get("/v1/ping") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "v1-branch"
      }
    }

    // When V2 is added to the ApiVersion enum, add a test here verifying
    // apiVersion(V2) under /v1 rejects, so the alternative branch (`~ apiVersion(V1)`) handles it.
  }

  describe("deprecated") {
    val route: Route = deprecated(complete("ok"))

    it("adds the Deprecation: true header") {
      Get("/") ~> route ~> check {
        header("Deprecation").map(_.value) shouldBe Some("true")
      }
    }

    it("does not add a Sunset header") {
      Get("/") ~> route ~> check {
        header("Sunset") shouldBe empty
      }
    }
  }

  describe("deprecatedWithSunset") {
    val sunset       = Instant.parse("2026-11-11T23:59:59Z")
    val route: Route =
      deprecatedWithSunset(sunset)(complete("ok"))

    it("adds the Deprecation: true header") {
      Get("/") ~> route ~> check {
        header("Deprecation").map(_.value) shouldBe Some("true")
      }
    }

    it("adds the Sunset header as an HTTP-date") {
      Get("/") ~> route ~> check {
        val expected = DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atOffset(ZoneOffset.UTC))
        header("Sunset").map(_.value) shouldBe Some(expected)
      }
    }

    it("does not interfere with the response body") {
      Get("/") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "ok"
      }
    }

    it("adds headers even on non-2xx complete responses") {
      val errorRoute: Route = deprecatedWithSunset(sunset)(complete(Status.NotFound -> "missing"))
      Get("/") ~> errorRoute ~> check {
        status shouldBe Status.NotFound
        header("Deprecation").map(_.value) shouldBe Some("true")
        header("Sunset") should not be empty
      }
    }

    it("Sunset header round-trips through RFC_1123_DATE_TIME") {
      Get("/") ~> route ~> check {
        val formatted = header("Sunset").map(_.value).getOrElse(fail("no Sunset header"))
        val parsed    = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(formatted))
        // RFC 1123 has second precision; the test sunset is on a second boundary already
        parsed shouldBe sunset
      }
    }
  }
}
