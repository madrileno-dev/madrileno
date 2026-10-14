package madrileno.utils.db.transactor

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader
import skunk.telemetry.{QueryAnalyzer, QueryCaptureConfig, TelemetryConfig}

final case class PgTelemetryConfig(captureParameters: Boolean = false)

object PgTelemetryConfig {
  given ConfigReader[PgTelemetryConfig] = deriveReader[PgTelemetryConfig]
}

object PgTelemetry {
  private val analyses: Cache[String, Option[QueryAnalyzer.Analysis]] =
    Scaffeine().maximumSize(1024).build[String, Option[QueryAnalyzer.Analysis]]()

  val analyzer: QueryAnalyzer = QueryAnalyzer(sql => analyses.get(sql, analyse))

  private def analyse(sql: String): Option[QueryAnalyzer.Analysis] =
    Some(QueryAnalyzer.Analysis(queryText = Some(sql), storedProcedureName = None, querySummary = QuerySummary.of(sql)))

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
