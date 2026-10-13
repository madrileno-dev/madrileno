package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.{Json, parser}
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Status, UrlForm}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.stir.server.Route
import pl.iterators.stir.testkit.ScalatestRouteTest

class HandlersSpec extends AnyFunSpec with Matchers with ScalatestRouteTest {

  override implicit def runtime: IORuntime = IORuntime.global

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private object probe extends Handlers {
    final case class Payload(name: String, count: Int) derives Codec.AsObject

    val route: Route =
      handleExceptions(exceptionHandler(pass)) {
        handleRejections(rejectionHandler(pass)) {
          concat(
            path("json") { post { entity(as[Payload]) { payload => complete(payload.name) } } },
            path("validated") { validate(false, "count must be positive") { complete("unreachable") } },
            path("illegal") { complete(IO.raiseError[String](new IllegalArgumentException("bad input"))) },
            path("boom") { complete(IO.raiseError[String](new RuntimeException("boom"))) },
            path("only-get") { get { complete("ok") } },
            path("https-only") { scheme("https") { complete("ok") } },
            path("int-param") { parameter("n".as[Int]) { n => complete(n.toString) } },
            path("required-param") { parameter("mode" ! "fast") { _ => complete("fast") } },
            path("header") { headerValueByName("X-Probe") { value => complete(value) } },
            path("parsed-header") {
              headerValue(h => if (h.name.toString == "X-Number") Some(h.value.toInt) else None) { n => complete(n.toString) }
            },
            path("cookie") { cookie("session") { c => complete(c.content) } },
            path("form") { formField("n".as[Int]) { n => complete(n.toString) } },
            path("forbidden") { authorize(false) { complete("unreachable") } }
          )
        }
      }
  }

  private def body: Json = parser.parse(responseAs[String]).fold(throw _, identity)

  private def field(name: String): Option[String] = body.hcursor.downField(name).as[String].toOption

  describe("rejectionHandler") {
    it("maps a circe decoding failure to an entity rejection naming the field") {
      Post("/json").withEntity("""{"name":"x","count":"many"}""") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:entity")
        field("title").getOrElse("") should startWith("The request entity was invalid: ")
        field("title").getOrElse("") should include("count")
      }
    }

    it("maps unparseable JSON to an entity rejection") {
      Post("/json").withEntity("""{not json""") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:entity")
        field("title").getOrElse("") should startWith("The request entity was invalid: ")
      }
    }

    it("maps a validate() failure to a validation error with the message as detail") {
      Get("/validated") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:static-validation-failed")
        field("detail") shouldBe Some("count must be positive")
      }
    }

    it("answers 405 with an Allow header when the method is not supported") {
      Post("/only-get") ~> probe.route ~> check {
        status shouldBe Status.MethodNotAllowed
        field("type") shouldBe Some("rejection:method")
        header("Allow").map(_.value) shouldBe Some("GET")
        body.hcursor.downField("supportedMethods").as[List[String]] shouldBe Right(List("GET"))
      }
    }

    it("lists the supported schemes when the request scheme is not allowed") {
      Get("http://localhost/https-only") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:scheme")
        field("supportedSchemes") shouldBe Some("https")
      }
    }

    it("names the query parameter that failed to parse") {
      Get("/int-param?n=abc") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:malformed-query-param")
        field("queryParamName") shouldBe Some("n")
      }
    }

    it("names the missing query parameter") {
      Get("/int-param") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:missing-query-param")
        field("queryParamName") shouldBe Some("n")
      }
    }

    it("reports the required value of a query parameter that does not match") {
      Get("/required-param?mode=slow") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:invalid-required-value-for-query-param")
        field("queryParamName") shouldBe Some("mode")
        field("requiredValue") shouldBe Some("fast")
      }
    }

    it("names the missing header") {
      Get("/header") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:missing-header")
        field("headerName") shouldBe Some("X-Probe")
      }
    }

    it("names the header whose value could not be parsed") {
      Get("/parsed-header").withHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Number"), "abc")) ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:malformed-header")
        field("headerName") shouldBe Some("X-Number")
      }
    }

    it("names the missing cookie") {
      Get("/cookie") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:missing-cookie")
        field("cookieName") shouldBe Some("session")
      }
    }

    it("names the missing form field") {
      Post("/form").withEntity(UrlForm("other" -> "1")) ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:missing-form-field")
        field("fieldName") shouldBe Some("n")
      }
    }

    it("names the form field that failed to parse") {
      Post("/form").withEntity(UrlForm("n" -> "abc")) ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:malformed-form-field")
        field("fieldName") shouldBe Some("n")
      }
    }

    it("answers 401 when authorization fails") {
      Get("/forbidden") ~> probe.route ~> check {
        status shouldBe Status.Unauthorized
        field("type") shouldBe Some("rejection:authorization-failed")
      }
    }
  }

  describe("exceptionHandler") {
    it("maps IllegalArgumentException to a 400 validation error with the message as detail") {
      Get("/illegal") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:static-validation-failed")
        field("detail") shouldBe Some("bad input")
      }
    }

    it("maps any other exception to a 500 without leaking the message") {
      Get("/boom") ~> probe.route ~> check {
        status shouldBe Status.InternalServerError
        field("type") shouldBe Some("error:internal-error")
        field("title") shouldBe Some("Internal error")
        field("detail") shouldBe None
        responseAs[String] should not include "boom"
      }
    }
  }
}
