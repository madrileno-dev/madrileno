package madrileno.utils.http

import cats.data.Kleisli
import cats.effect.IO
import madrileno.utils.db.transactor.{DatabaseTime, DatabaseUse}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import org.http4s.{HttpApp, Request, Response}

import scala.concurrent.duration.FiniteDuration

/** One log line per request: status, how long it took until the response was ready, and how much of that went to the database. */
object RequestTiming extends LoggingSupport {

  def apply(app: HttpApp[IO], databaseTime: DatabaseTime)(using TelemetryContext): HttpApp[IO] =
    Kleisli { request =>
      IO.monotonic.flatMap { started =>
        databaseTime.tracking(app.run(request)).flatMap { case (outcome, used) =>
          IO.monotonic.flatMap(ended => report(request, outcome, ended - started, used)) *> IO.fromEither(outcome)
        }
      }
    }

  private def report(
    request: Request[IO],
    outcome: Either[Throwable, Response[IO]],
    elapsed: FiniteDuration,
    used: DatabaseUse
  )(using TelemetryContext
  ): IO[Unit] = {
    val path     = request.uri.path.renderString
    val status   = outcome.fold(_ => "failed", _.status.code.toString)
    val database = used.calls match {
      case 0 => "no database calls"
      case 1 => s"${used.time.toMillis} ms of it in 1 database call"
      case n => s"${used.time.toMillis} ms of it in $n database calls"
    }
    val message = s"${request.method.name} $path $status in ${elapsed.toMillis} ms, $database"
    val fields  = Map(
      "http.request.method" -> request.method.name,
      "url.path"            -> path,
      "event.duration"      -> elapsed.toNanos.toString,
      "db.calls"            -> used.calls.toString,
      "db.duration"         -> used.time.toNanos.toString
    ) ++ outcome.toOption.map(response => "http.response.status_code" -> response.status.code.toString)
    if (path.startsWith("/v1/health-check")) logger.debug(fields)(message) else logger.info(fields)(message)
  }
}
