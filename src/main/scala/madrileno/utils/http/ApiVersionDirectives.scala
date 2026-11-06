package madrileno.utils.http

import cats.effect.kernel.Unique
import org.http4s.{Header, Headers}
import org.typelevel.ci.*
import org.typelevel.vault.Key
import pl.iterators.stir.server.directives.BasicDirectives.{extractRequest, mapRequest, mapResponseHeaders, pass, provide}
import pl.iterators.stir.server.directives.PathDirectives.pathPrefix
import pl.iterators.stir.server.directives.RouteDirectives.reject
import pl.iterators.stir.server.{Directive, Directive0, Directive1}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

private val apiVersionAttrKey: Key[ApiVersion] = new Key[ApiVersion](new Unique.Token)

trait ApiVersionDirectives {

  require(ApiVersion.values.nonEmpty, "ApiVersion must declare at least one case to mount under")

  val apiVersionPrefix: Directive0 =
    ApiVersion.values.toList
      .map(v => pathPrefix(v.urlSegment) & mapRequest(_.withAttribute(apiVersionAttrKey, v)))
      .reduce(_ | _)

  val currentApiVersion: Directive1[ApiVersion] =
    extractRequest.flatMap { req =>
      req.attributes.lookup(apiVersionAttrKey).fold(Directive(_ => reject): Directive1[ApiVersion])(provide)
    }

  def apiVersion(target: ApiVersion): Directive0 =
    currentApiVersion.flatMap { current =>
      if (current == target) pass else Directive(_ => reject)
    }

  // RFC 9745 `Deprecation: true` + RFC 8594 `Sunset: <http-date>`
  private val deprecationHeader = Header.Raw(ci"Deprecation", "true")

  val deprecated: Directive0 =
    mapResponseHeaders(_ ++ Headers(deprecationHeader))

  def deprecatedWithSunset(sunset: Instant): Directive0 = {
    val sunsetStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atOffset(ZoneOffset.UTC))
    mapResponseHeaders(_ ++ Headers(deprecationHeader, Header.Raw(ci"Sunset", sunsetStr)))
  }
}

object ApiVersionDirectives extends ApiVersionDirectives
