package madrileno.healthcheck.services

import cats.effect.IO
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.main.AppConfig

import scala.concurrent.duration.*

class HealthCheckService(appConfig: AppConfig, postgresProbe: PostgresProbe) {
  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def readiness(): IO[DepStatus] = postgresProbe.check(within = 1.second).map(_.status)
}
