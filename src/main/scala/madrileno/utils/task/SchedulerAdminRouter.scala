package madrileno.utils.task

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.http.BaseRouter
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType, Response}
import pl.iterators.stir.server.Route

class SchedulerAdminRouter(
  recurringTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]],
  schedulerClient: SchedulerClient,
  clock: Clock[IO])
    extends BaseRouter {

  val routes: Route = get {
    path("jobs.json")(complete(report)) ~ ((pathPrefix("jobs") & pathEndOrSingleSlash) | path("jobs.html")) {
      complete {
        report.map { report =>
          Response[IO]()
            .withEntity(JobsPage.render(report))(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
        }
      }
    }
  }

  private def report: IO[JobsReport] =
    (schedulerClient.listTasks, clock.realTimeInstant).mapN { (rows, now) =>
      JobsReport(recurringTasks, oneTimeTasks, customTasks, rows, schedulerClient.config, now)
    }
}
