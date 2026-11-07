package madrileno.healthcheck.services

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import madrileno.healthcheck.routers.dto.{DepCheck, DepStatus}
import madrileno.main.AppConfig
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import scala.concurrent.duration.*

class HealthCheckService(appConfig: AppConfig, postgresProbe: PostgresProbe)(using TelemetryContext) extends LoggingSupport {
  private val lastReadiness = Ref.unsafe[IO, Option[(FiniteDuration, DepStatus)]](None)

  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def readiness(): IO[DepStatus] =
    IO.monotonic.flatMap { now =>
      lastReadiness.get.map {
        case Some((checkedAt, status)) if now - checkedAt < HealthCheckService.readinessValidFor => status
        case _                                                                                   => DepStatus.Down
      }
    }

  val readinessLifecycle: Resource[IO, Unit] =
    (postgresProbe.check.flatMap(record) *> IO.sleep(HealthCheckService.readinessInterval)).foreverM.background.void

  private def record(check: DepCheck): IO[Unit] =
    IO.monotonic.flatMap { now =>
      lastReadiness.getAndSet(Some((now, check.status))).flatMap { previous =>
        val wasDown = previous.exists(_._2 == DepStatus.Down)
        check.status match {
          case DepStatus.Down if !wasDown => logger.error(s"Readiness is Down, Postgres check failed: ${check.error.getOrElse("no reason given")}")
          case DepStatus.Up if wasDown    => logger.info("Readiness is Up again: Postgres answers")
          case _                          => IO.unit
        }
      }
    }
}

object HealthCheckService {
  val readinessInterval: FiniteDuration = 2.seconds
  val readinessValidFor: FiniteDuration = readinessInterval * 3
}
