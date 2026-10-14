package madrileno.utils.db.transactor

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource
import skunk.telemetry.{QueryCaptureConfig, TelemetryConfig}

class PgTelemetrySpec extends AnyWordSpec with Matchers with OptionValues {

  private val base =
    """
      |host = "localhost"
      |user = "postgres"
      |database = "madrileno"
      |""".stripMargin

  "PgConfig" should {
    "default to not capturing parameters" in {
      ConfigSource.string(base).loadOrThrow[PgConfig].telemetry shouldBe PgTelemetryConfig(captureParameters = false)
    }

    "read telemetry.capture-parameters" in {
      ConfigSource
        .string(base + "telemetry.capture-parameters = true")
        .loadOrThrow[PgConfig]
        .telemetry shouldBe PgTelemetryConfig(captureParameters = true)
    }
  }

  "PgTelemetry.config" should {
    val quiet   = PgTelemetry.config(PgTelemetryConfig(captureParameters = false))
    val verbose = PgTelemetry.config(PgTelemetryConfig(captureParameters = true))

    "disable protocol and pool spans" in {
      quiet.protocolSpans shouldBe TelemetryConfig.ProtocolSpans.Disabled
      quiet.poolSpans shouldBe TelemetryConfig.PoolSpans.Disabled
    }

    "keep parameterised query text" in {
      quiet.captureQuery.queryTextPolicy shouldBe QueryCaptureConfig.QueryTextPolicy.SemconvRecommended
      verbose.captureQuery.queryTextPolicy shouldBe QueryCaptureConfig.QueryTextPolicy.SemconvRecommended
    }

    "capture parameter values only when asked" in {
      quiet.captureQuery.queryParametersPolicy shouldBe QueryCaptureConfig.QueryParametersPolicy.None
      verbose.captureQuery.queryParametersPolicy shouldBe QueryCaptureConfig.QueryParametersPolicy.All
    }

    "summarise queries by verb and table and pass the text through" in {
      val analysis = quiet.queryAnalyzer.analyze("SELECT * FROM task WHERE id = $1").value
      analysis.querySummary shouldBe Some("SELECT task")
      analysis.queryText shouldBe Some("SELECT * FROM task WHERE id = $1")
      analysis.storedProcedureName shouldBe None
    }

    "omit the text of a statement it cannot summarise, since it may carry literals" in {
      quiet.queryAnalyzer.analyze("""NOTIFY events, '{"email":"a@b.c"}'""") shouldBe None
      quiet.queryAnalyzer.analyze("LISTEN events") shouldBe None
    }
  }
}
