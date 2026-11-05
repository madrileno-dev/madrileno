package madrileno.utils.http

import org.http4s.headers.{Accept, `Content-Type`}
import org.http4s.{Header, MediaRange, MediaType}
import org.typelevel.ci.*
import pl.iterators.stir.server.{Directive1, UnacceptedResponseContentTypeRejection}

trait ContentNegotiationDirectives { self: BaseRouter =>
  def negotiate(first: MediaType, more: MediaType*): Directive1[MediaType] =
    respondWithHeader(Header.Raw(ci"Vary", "Accept")) & extractRequest.flatMap[Tuple1[MediaType]] { request =>
      val available = first +: more
      val chosen    = request.headers.get[Accept] match {
        case None         => Some(first)
        case Some(accept) =>
          val ranked = available.zipWithIndex.flatMap { case (mediaType, order) =>
            accept.values.toList
              .filter(range => matches(range.mediaRange, mediaType))
              .maxByOption(range => (specificity(range.mediaRange), range.qValue.thousandths))
              .map(range => (mediaType, range.qValue.thousandths, order))
          }
          ranked.filter(_._2 > 0).sortBy { case (_, quality, order) => (-quality, order) }.headOption.map(_._1)
      }
      chosen match {
        case Some(mediaType) => provide(mediaType)
        case None            =>
          val rejected: Directive1[MediaType] = reject(UnacceptedResponseContentTypeRejection(available.map(`Content-Type`(_)).toSet))
          rejected
      }
    }

  private def matches(range: MediaRange, mediaType: MediaType): Boolean =
    range.satisfiedBy(mediaType) && range.extensions.forall {
      case ("charset", _) => true
      case (key, value)   => mediaType.extensions.get(key).contains(value)
    }

  private def specificity(range: MediaRange): Int = range match {
    case mediaType: MediaType       => 2 + mediaType.extensions.size
    case _ if range.mainType == "*" => 0
    case _                          => 1
  }
}
