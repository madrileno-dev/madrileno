package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import io.circe.{Json, parser}
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.observability.TelemetryContext
import org.http4s.headers.{Accept, `Content-Type`}
import org.http4s.server.middleware.EntityLimiter.EntityTooLarge
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{EntityEncoder, MediaType, Request, Status, UrlForm}
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

    private val wsb: WebSocketBuilder2[IO] = WebSocketBuilder2[IO].unsafeRunSync()

    val route: Route =
      handleExceptions(exceptionHandler(pass)) {
        handleRejections(rejectionHandler(pass)) {
          concat(
            path("json") { post { entity(as[Payload]) { payload => complete(payload.name) } } },
            path("json-out") { get { complete(Payload("x", 1)) } },
            path("ws") { handleWebSocketMessages(wsb, Stream.empty, _.drain) },
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

  private def jsonPost(json: String): Request[IO] =
    Post("/json").withEntity(json)(using EntityEncoder.stringEncoder).withContentType(`Content-Type`(MediaType.application.json))

  private def body: Json = parser.parse(responseAs[String]).fold(throw _, identity)

  private def field(name: String): Option[String] = body.hcursor.downField(name).as[String].toOption

  describe("rejectionHandler") {
    it("maps a circe decoding failure to a validation error naming the field, without echoing the body") {
      jsonPost("""{"name":"secret-name","count":"many"}""") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:static-validation-failed")
        field("path") shouldBe Some(".count")
        field("detail").getOrElse("") should startWith(".count: ")
        responseAs[String] should not include "secret-name"
      }
    }

    it("maps unparseable JSON to malformed request content") {
      jsonPost("""{not json""") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:malformed-request-content")
      }
    }

    it("answers 415 with the supported content types when the body is not JSON") {
      Post("/json").withEntity("""{"name":"x","count":1}""") ~> probe.route ~> check {
        status shouldBe Status.UnsupportedMediaType
        field("type") shouldBe Some("rejection:unsupported-request-content-type")
        body.hcursor.downField("supportedContentTypes").as[List[String]] shouldBe Right(List("application/json"))
      }
    }

    it("answers 400 when the body is missing") {
      Post("/json") ~> probe.route ~> check {
        status shouldBe Status.BadRequest
        field("type") shouldBe Some("rejection:request-entity-expected")
      }
    }

    it("answers 413 with the limit when the body exceeds the server's request size limit") {
      Post("/json")
        .withBodyStream(Stream.raiseError[IO](EntityTooLarge(16)))
        .withContentType(`Content-Type`(MediaType.application.json)) ~> probe.route ~> check {
        status shouldBe Status.PayloadTooLarge
        field("type") shouldBe Some("rejection:payload-too-large")
        body.hcursor.downField("limitBytes").as[Long] shouldBe Right(16L)
      }
    }

    it("answers 406 with the available content types when the client accepts none of them") {
      Get("/json-out").withHeaders(Accept(MediaType.text.plain)) ~> probe.route ~> check {
        status shouldBe Status.NotAcceptable
        field("type") shouldBe Some("rejection:unaccepted-response-content-type")
        body.hcursor.downField("supportedContentTypes").as[List[String]] shouldBe Right(List("application/json"))
      }
    }

    it("answers 426 with an Upgrade header when a WebSocket route gets a plain request") {
      Get("/ws") ~> probe.route ~> check {
        status shouldBe Status.UpgradeRequired
        field("type") shouldBe Some("rejection:expected-web-socket-request")
        header("Upgrade").map(_.value) shouldBe Some("websocket")
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
