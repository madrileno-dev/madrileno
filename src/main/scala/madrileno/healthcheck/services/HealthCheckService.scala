package madrileno.healthcheck.services

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import madrileno.healthcheck.routers.dto.DepStatus
import madrileno.main.AppConfig

import scala.concurrent.duration.*

class HealthCheckService(appConfig: AppConfig, postgresProbe: PostgresProbe) {
  private val lastReadiness = Ref.unsafe[IO, Option[(FiniteDuration, Deferred[IO, DepStatus])]](None)

  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def readiness(): IO[DepStatus] =
    IO.uncancelable { poll =>
      (IO.monotonic, Deferred[IO, DepStatus]).flatMapN { (now, mine) =>
        lastReadiness.modify {
          case Some((startedAt, shared)) if now - startedAt < HealthCheckService.readinessReusedFor =>
            (Some((startedAt, shared)), poll(shared.get))
          case _ =>
            (Some((now, mine)), postgresProbe.check(within = 1.second).flatMap(result => mine.complete(result.status)).start *> poll(mine.get))
        }.flatten
      }
    }
}

object HealthCheckService {
  val readinessReusedFor: FiniteDuration = 1.second
}
