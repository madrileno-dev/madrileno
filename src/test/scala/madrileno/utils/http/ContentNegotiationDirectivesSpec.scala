package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{Header, Headers, MediaType, Method, Request, Response, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.*
import pl.iterators.stir.server.{RejectionHandler, Route, ToHttpRoutes}

class ContentNegotiationDirectivesSpec extends AnyFunSpec with Matchers {

  private given IORuntime = IORuntime.global

  private val router = new BaseRouter {}
  import router.*

  private val routes: Route =
    handleRejections(RejectionHandler.newBuilder().result().seal) {
      (get & path("report") & negotiate(MediaType.application.json, MediaType.text.html)) { chosen =>
        complete(Response[IO]().putHeaders(Header.Raw(ci"X-Chosen", s"${chosen.mainType}/${chosen.subType}")))
      }
    }

  private def run(accept: Option[String]): Response[IO] =
    routes.toHttpRoutes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/report"), headers = Headers(accept.map(Header.Raw(ci"Accept", _)).toList)))
      .unsafeRunSync()

  private def chosen(accept: Option[String]): String = run(accept).headers.get(ci"X-Chosen").fold("none")(_.head.value)

  describe("negotiate") {
    it("serves the first representation when the request has no Accept header or accepts anything") {
      chosen(None) shouldBe "application/json"
      chosen(Some("*/*")) shouldBe "application/json"
    }

    it("serves the representation the request names") {
      chosen(Some("application/json")) shouldBe "application/json"
      chosen(Some("text/html")) shouldBe "text/html"
      chosen(Some("text/*")) shouldBe "text/html"
    }

    it("serves HTML to a browser, whose Accept header ranks it above the wildcard") {
      chosen(Some("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")) shouldBe "text/html"
    }

    it("follows the quality values") {
      chosen(Some("text/html;q=0.5, application/json")) shouldBe "application/json"
      chosen(Some("application/json;q=0.2, text/html;q=0.9")) shouldBe "text/html"
    }

    it("lets the most specific range decide, so q=0 excludes a type the wildcard would accept") {
      chosen(Some("application/json;q=0, */*")) shouldBe "text/html"
    }

    it("matches a range with parameters only to a type that carries them, charset aside") {
      chosen(Some("application/json;charset=utf-8, text/html;q=0.5")) shouldBe "application/json"
      chosen(Some("application/json;version=2, text/html;q=0.5")) shouldBe "text/html"
      chosen(Some("text/html;level=1, application/json;q=0.5")) shouldBe "application/json"
    }

    it("lets the higher quality decide between ranges that are equally specific, as stir's own check does") {
      chosen(Some("application/json;q=0, application/json, text/html;q=0.1")) shouldBe "application/json"
    }

    it("answers 406 when the request accepts none of the representations") {
      run(Some("image/png")).status shouldBe Status.NotAcceptable
    }

    it("tells caches that the response depends on the Accept header") {
      run(Some("text/html")).headers.get(ci"Vary").map(_.head.value) shouldBe Some("Accept")
      run(None).headers.get(ci"Vary").map(_.head.value) shouldBe Some("Accept")
    }
  }
}
