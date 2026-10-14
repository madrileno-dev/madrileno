package madrileno.utils.task

import org.typelevel.otel4s.trace.{SpanContext, TraceFlags, TraceState}

object TraceParent {
  private val Version = "00"

  def of(context: SpanContext): String =
    s"$Version-${context.traceIdHex}-${context.spanIdHex}-${context.traceFlags.toHex}"

  def parse(value: String): Option[SpanContext] =
    value.split("-", -1).toList match {
      case Version :: traceIdHex :: spanIdHex :: flagsHex :: Nil =>
        for {
          traceId <- SpanContext.TraceId.fromHex(traceIdHex).filter(SpanContext.TraceId.isValid)
          spanId  <- SpanContext.SpanId.fromHex(spanIdHex).filter(SpanContext.SpanId.isValid)
          flags   <- parseFlags(flagsHex)
        } yield SpanContext(traceId, spanId, flags, TraceState.empty, remote = true)
      case _ => None
    }

  private def parseFlags(hex: String): Option[TraceFlags] =
    Option.when(hex.matches("[0-9a-fA-F]{2}"))(TraceFlags.fromByte(Integer.parseInt(hex, 16).toByte))
}
