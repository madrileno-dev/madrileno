package madrileno.healthcheck.services

import cats.effect.{Clock, IO}
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.{DepCheck, DepStatus}
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

class PostgresProbe(
  healthCheckRepository: HealthCheckRepository,
  transactor: Transactor,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {

  def check(within: FiniteDuration): IO[DepCheck] =
    clock
      .timed(transactor.inSession(healthCheckRepository.version()))
      .start
      .flatMap { probe =>
        probe.joinWithNever.timeoutTo(within, probe.cancel.start *> IO.raiseError(new TimeoutException(s"Postgres did not answer within $within")))
      }
      .map { case (rtt, _) => DepCheck(DepStatus.Up, Some(rtt.toMillis), None) }
      .handleErrorWith { t =>
        logger.error(t)("Postgres health check failed").as(DepCheck(DepStatus.Down, None, Some(t.getMessage)))
      }
}
