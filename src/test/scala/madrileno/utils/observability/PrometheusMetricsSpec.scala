package madrileno.utils.observability

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.oteljava.OtelJava

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{ServerSocket, URI}
import scala.jdk.CollectionConverters.*

class PrometheusMetricsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private def freePort: Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def scrape(port: Int): IO[HttpResponse[String]] =
    IO.blocking(
      HttpClient
        .newHttpClient()
        .send(HttpRequest.newBuilder(URI.create(s"http://localhost:$port/metrics")).build(), HttpResponse.BodyHandlers.ofString())
    )

  "OTEL_METRICS_EXPORTER=prometheus" should {
    "serve the app's metrics for scraping at /metrics on the exporter's own port" in {
      val port       = freePort
      val properties = Map(
        "otel.sdk.disabled"             -> "false",
        "otel.metrics.exporter"         -> "prometheus",
        "otel.exporter.prometheus.port" -> port.toString,
        "otel.exporter.prometheus.host" -> "localhost",
        "otel.traces.exporter"          -> "none",
        "otel.logs.exporter"            -> "none",
        "otel.service.name"             -> "prometheus-spec"
      )
      OtelJava.autoConfigured[IO](_.addPropertiesSupplier(() => properties.asJava).disableShutdownHook()).use { otel =>
        for {
          meter    <- otel.meterProvider.get("prometheus-spec")
          counter  <- meter.counter[Long]("orders.placed").create
          _        <- counter.add(3)
          response <- scrape(port)
        } yield {
          response.statusCode shouldBe 200
          response.body should include("orders_placed_total")
        }
      }
    }
  }
}
