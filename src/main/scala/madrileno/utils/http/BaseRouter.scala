package madrileno.utils.http

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream
import madrileno.utils.json.JsonProtocol
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.pagination.{Cursor, Page}
import org.http4s.Status
import pl.iterators.kebs.enums.KebsEnum
import pl.iterators.kebs.http4sstir.matchers.KebsHttp4sStirMatchers
import pl.iterators.kebs.http4sstir.unmarshallers.{KebsHttp4sStirEnumUnmarshallers, KebsHttp4sStirUnmarshallers, KebsHttp4sStirValueEnumUnmarshallers}
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Directives
import pl.iterators.stir.server.directives.WebSocketDirectives

import java.net.URI

trait BaseRouter
    extends JsonProtocol
    with Directives
    with WebSocketDirectives
    with KebsHttp4sStirMatchers
    with KebsHttp4sStirUnmarshallers
    with KebsHttp4sStirEnumUnmarshallers
    with KebsHttp4sStirValueEnumUnmarshallers
    with KebsEnum
    with PaginationDirectives
    with ApiVersionDirectives {
  export Status.*

  given [A: Encoder]: Encoder.AsObject[Page[A]]   = Encoder.AsObject.derived
  given [A: Decoder]: Decoder[Page[A]]            = Decoder.derived
  given [A: Encoder]: Encoder.AsObject[Cursor[A]] = Encoder.AsObject.derived
  given [A: Decoder]: Decoder[Cursor[A]]          = Decoder.derived

  def error[E: Encoder](
    status: Status,
    typeTag: String,
    title: String,
    detail: Option[String] = None,
    extension: E = ()
  )(using tc: TelemetryContext
  ): IO[ToResponseMarshallable] = {
    tc.tracer.currentSpanContext.map { spanContext =>
      val error =
        Error(
          Some(new URI(s"result:$typeTag")),
          Some(status),
          Some(title),
          detail,
          spanContext.map(c => new URI(s"trace-id:${c.traceIdHex}")),
          extension
        )
      status -> summon[Encoder[Error[E]]](error)
    }
  }

  extension [A](source: Stream[IO, A]) def droppingBuffer(capacity: Int): Stream[IO, A] =
    Stream.eval(Queue.bounded[IO, Option[A]](capacity)).flatMap { q =>
      val pump = source.evalMap(a => q.tryOffer(Some(a)).void) ++ Stream.eval(q.offer(None))
      Stream.fromQueueNoneTerminated(q).concurrently(pump)
    }
}
