package madrileno.utils.task

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.http.BaseRouter
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType, Response, Status}
import pl.iterators.stir.server.Route

class SchedulerAdminRouter(
  recurringTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]],
  schedulerClient: SchedulerClient,
  clock: Clock[IO])
    extends BaseRouter {

  val routes: Route = get {
    path("jobs.json")(complete(report.map((status, report) => Response[IO](status).withEntity(report)))) ~ path("jobs.html") {
      complete {
        report.map { (status, report) =>
          Response[IO](status)
            .withEntity(JobsPage.render(report))(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
        }
      }
    }
  }

  private def report: IO[(Status, JobsReport)] =
    (schedulerClient.listTasks.attempt, clock.realTimeInstant).mapN {
      case (Right(rows), now) => Status.Ok                 -> JobsReport(recurringTasks, oneTimeTasks, customTasks, rows, schedulerClient.config, now)
      case (Left(error), now) => Status.ServiceUnavailable -> JobsReport.withoutTaskTable(recurringTasks, oneTimeTasks, customTasks, error, now)
    }
}
