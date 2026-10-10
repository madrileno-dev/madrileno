package madrileno.utils.observability.admin

import cats.effect.unsafe.IORuntime
import cats.effect.{FiberSnapshot, IO}
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

class ThreaddumpAdminRouter(
  takeFiberSnapshot: IO[FiberSnapshot],
  override protected val rateLimiterRuntime: RateLimiterRuntime
)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash & rateLimited("admin.threaddump", to = 20, within = 1.minute)) {
      complete {
        ThreaddumpAdminRouter.retryingOnHandoff(takeFiberSnapshot).flatMap {
          case Some(snapshot) => IO.blocking(dump(snapshot)).map[ToResponseMarshallable](Ok -> _)
          case None           =>
            error(ServiceUnavailable, "fiber-snapshot-unavailable", "The fiber snapshot kept racing a blocking handoff; try again")
        }
      }
    }

  private def dump(snapshot: FiberSnapshot): ThreaddumpDto = {
    val mx         = ManagementFactory.getThreadMXBean
    val infos      = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported, mx.isSynchronizerUsageSupported)
    val jvmThreads = infos.toList.map(JvmThreadDto.apply).sortBy(_.threadName)
    val workers    = snapshot.workers.toList
      .map { case (worker, fibers) =>
        WorkerFibersDto(worker.thread.getName, worker.index, fibers.map(FiberInfoDto.apply))
      }
      .sortBy(_.workerIndex)
    val external = snapshot.external.map(FiberInfoDto.apply)
    ThreaddumpDto(jvmThreads, FiberDumpDto(workers, external))
  }
}

object ThreaddumpAdminRouter {
  val SnapshotAttempts: Int              = 5
  val SnapshotRetryPause: FiniteDuration = 10.millis

  def apply(runtime: IORuntime, rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext): ThreaddumpAdminRouter =
    new ThreaddumpAdminRouter(IO.blocking(runtime.liveFiberSnapshot()), rateLimiterRuntime)

  def retryingOnHandoff(
    take: IO[FiberSnapshot],
    attempts: Int = SnapshotAttempts,
    pause: FiniteDuration = SnapshotRetryPause
  ): IO[Option[FiberSnapshot]] =
    take.map(Some(_)).recoverWith {
      case _: NullPointerException if attempts > 1 => IO.sleep(pause) *> retryingOnHandoff(take, attempts - 1, pause)
      case _: NullPointerException                 => IO.none
    }
}
