package madrileno.healthcheck.services

import cats.effect.{Clock, IO}
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.dto.{DepCheck, DepStatus}
import madrileno.utils.db.transactor.Transactor

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

class PostgresProbe(
  healthCheckRepository: HealthCheckRepository,
  transactor: Transactor,
  clock: Clock[IO]) {

  def check: IO[DepCheck] =
    clock
      .timed(
        transactor
          .inNewConnection(healthCheckRepository.version())
          .timeoutTo(PostgresProbe.timeout, IO.raiseError(new TimeoutException(s"no answer within ${PostgresProbe.timeout}")))
      )
      .map { case (rtt, _) => DepCheck(DepStatus.Up, Some(rtt.toMillis), None) }
      .handleError(t => DepCheck(DepStatus.Down, None, Some(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))))
}

object PostgresProbe {
  val timeout: FiniteDuration = 2.seconds
}
