package madrileno.utils.db.transactor

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader
import skunk.telemetry.{QueryAnalyzer, QueryCaptureConfig, TelemetryConfig}

final case class PgTelemetryConfig(captureParameters: Boolean = false)

object PgTelemetryConfig {
  given ConfigReader[PgTelemetryConfig] = deriveReader[PgTelemetryConfig]
}

object PgTelemetry {
  val analyzer: QueryAnalyzer = QueryAnalyzer { sql =>
    QuerySummary.of(sql).map { summary =>
      QueryAnalyzer.Analysis(queryText = Some(sql), storedProcedureName = None, querySummary = Some(summary))
    }
  }

  def config(telemetry: PgTelemetryConfig): TelemetryConfig = {
    val parameters =
      if (telemetry.captureParameters) QueryCaptureConfig.QueryParametersPolicy.All
      else QueryCaptureConfig.QueryParametersPolicy.None
    TelemetryConfig.default
      .withProtocolSpans(TelemetryConfig.ProtocolSpans.Disabled)
      .withPoolSpans(TelemetryConfig.PoolSpans.Disabled)
      .withCaptureQuery(QueryCaptureConfig.recommended.withQueryParametersPolicy(parameters))
      .withQueryAnalyzer(analyzer)
  }
}
