package madrileno.utils.observability.admin

import cats.effect.unsafe.IORuntime
import cats.effect.{FiberSnapshot, IO}
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route
import retry.RetryPolicies.*
import retry.{HandlerDecision, RetryDetails, RetryPolicy, retryingOnErrors}

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

class ThreaddumpAdminRouter(runtime: IORuntime, override protected val rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash & rateLimited("admin.threaddump", to = 20, within = 1.minute)) {
      complete {
        ThreaddumpAdminRouter
          .retryingOnHandoff(IO.blocking(runtime.liveFiberSnapshot()))
          .flatMap(snapshot => IO.blocking(dump(snapshot)))
          .map(Ok -> _)
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
  private[admin] val snapshotRetryPolicy: RetryPolicy[IO, Any] =
    limitRetries[IO](4).join(constantDelay[IO](10.millis))

  def retryingOnHandoff(take: IO[FiberSnapshot], policy: RetryPolicy[IO, Any] = snapshotRetryPolicy): IO[FiberSnapshot] =
    retryingOnErrors(take)(
      policy = policy,
      errorHandler = (e: Throwable, _: RetryDetails) =>
        IO.pure(e match {
          case _: NullPointerException => HandlerDecision.Continue
          case _                       => HandlerDecision.Stop
        })
    )
}
