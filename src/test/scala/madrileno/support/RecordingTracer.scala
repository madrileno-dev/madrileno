package madrileno.support

import cats.effect.IO
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import madrileno.utils.observability.TelemetryContext
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters.*

final class RecordingTracer(exporter: InMemorySpanExporter, val context: TelemetryContext) {
  def spans: IO[List[SpanData]] = IO(exporter.getFinishedSpanItems.asScala.toList)

  def spansNamed(name: String): IO[List[SpanData]] = spans.map(_.filter(_.getName == name))
}

object RecordingTracer {
  def create: IO[RecordingTracer] = {
    val exporter = InMemorySpanExporter.create()
    val sdk      = OpenTelemetrySdk
      .builder()
      .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
      .build()
    OtelJava.fromJOpenTelemetry[IO](sdk).flatMap(_.tracerProvider.get("madrileno.test")).map { tracer =>
      new RecordingTracer(exporter, TelemetryContext(Meter.noop[IO], tracer, sdk))
    }
  }

  def longAttribute(span: SpanData, key: String): Option[Long] =
    Option(span.getAttributes.get(AttributeKey.longKey(key))).map(_.longValue)
}
