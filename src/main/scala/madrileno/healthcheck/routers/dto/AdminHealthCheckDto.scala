package madrileno.healthcheck.routers.dto

import madrileno.utils.json.JsonProtocol.*

enum DepStatus derives Encoder, Decoder {
  case Up, Down, Disabled
}

final case class DepCheck(
  status: DepStatus,
  latencyMs: Option[Long],
  error: Option[String])
    derives Encoder.AsObject,
      Decoder

final case class AdminHealthCheckDto(
  status: DepStatus,
  postgres: DepCheck,
  smtp: DepCheck)
    derives Encoder.AsObject,
      Decoder

final case class ReadinessDto(status: DepStatus) derives Encoder.AsObject, Decoder
