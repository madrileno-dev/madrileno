package madrileno.utils.task

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.http.BaseRouter
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType, Response}
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class SchedulerAdminRouter(
  recurringTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]],
  schedulerClient: SchedulerClient,
  clock: Clock[IO])
    extends BaseRouter {

  val routes: Route = (get & pathPrefix("jobs") & pathEndOrSingleSlash & negotiate(MediaType.application.json, MediaType.text.html)) { mediaType =>
    complete {
      report.map[ToResponseMarshallable] { report =>
        if (mediaType == MediaType.text.html)
          Response[IO]()
            .withEntity(JobsPage.render(report))(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
        else report
      }
    }
  }

  private def report: IO[JobsReport] =
    (schedulerClient.listTasks, clock.realTimeInstant).mapN { (rows, now) =>
      JobsReport(recurringTasks, oneTimeTasks, customTasks, rows, schedulerClient.config, now)
    }
}
