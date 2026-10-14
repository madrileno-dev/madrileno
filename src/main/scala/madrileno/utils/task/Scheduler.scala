package madrileno.utils.task

import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import madrileno.utils.db.transactor.{DB, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter, UpDownCounter}
import org.typelevel.otel4s.trace.StatusCode

import java.net.InetAddress

class Scheduler(transactor: Transactor, config: SchedulerConfig = SchedulerConfig())(using Clock[IO], TelemetryContext) extends LoggingSupport {

  val client: SchedulerClient = new SchedulerClient(new ClientSchedulerRepository(), transactor)

  private val concurrency          = config.concurrency
  private val pollingInterval      = config.pollingInterval
  private val heartbeatInterval    = config.heartbeatInterval
  private val missedHeartbeatLimit = config.missedHeartbeatLimit
  private val retryBaseDelay       = config.retryBaseDelay
  private val retryBackoffRate     = config.retryBackoffRate
  private val retryMaxDelay        = config.retryMaxDelay
  private val maxRetries           = config.maxRetries

  private val schedulerNameToUse: IO[String] = config.schedulerName match {
    case Some(name) => IO.pure(name)
    case None       =>
      IO.blocking {
        InetAddress.getLocalHost.getHostName
      }.recover { case _ =>
        "unknown-host"
      }
  }

  def run(
    recurringTasks: List[Task[?]] = Nil,
    oneTimeTasks: List[OneTimeTask[?]] = Nil,
    customTasks: List[CustomTask[?]] = Nil
  ): Resource[IO, Unit] = {
    (for {
      schedulerName <- Resource.eval(schedulerNameToUse)
      meters        <- Resource.eval(SchedulerMeters.create(summon[TelemetryContext].meter))
      repository    <- Resource.eval(setup(schedulerName, recurringTasks, oneTimeTasks, customTasks))
      _             <- mainLoop(repository, meters)
    } yield ()).onFinalize {
      logger.info("Shutting down scheduler...")
    }
  }

  private def setup(
    schedulerName: String,
    startTasks: List[Task[?]],
    oneTimeTasks: List[OneTimeTask[?]],
    customTasks: List[CustomTask[?]]
  ): IO[SchedulerRepository] = {
    val repository = new SchedulerRepository(schedulerName, startTasks, oneTimeTasks, customTasks)
    logger.info(
      s"Starting scheduler with concurrency=$concurrency, pollingInterval=$pollingInterval, heartbeatInterval=$heartbeatInterval, missedHeartbeatLimit=$missedHeartbeatLimit, " +
        s"retryBaseDelay=$retryBaseDelay, retryBackoffRate=$retryBackoffRate, retryMaxDelay=$retryMaxDelay, maxRetries=$maxRetries, " +
        s"initial tasks=${startTasks.map(_.taskId)}, " +
        s"registered one time tasks=${oneTimeTasks.map(_.descriptor.taskName)}, registered custom tasks=${customTasks.map(_.descriptor.taskName)}"
    ) *>
      transactor
        .inSession {
          startTasks.traverse(repository.registerOnStartup)
        }
        .as(repository)
  }

  private def reservePermits(sem: Semaphore[IO], max: Int): IO[Int] = {
    0.tailRecM { acquired =>
      if (acquired >= max)
        IO.pure(Right(acquired))
      else
        sem.tryAcquire.map {
          case true  => Left(acquired + 1)
          case false => Right(acquired)
        }
    }
  }

  private def heartbeatLoop(repository: SchedulerRepository, task: Task[?]): IO[Unit] = {
    def oneBeat: IO[Unit] =
      transactor
        .inSession {
          repository.updateHeartbeat(task)
        }
        .handleErrorWith { e =>
          logger.error(e)(s"Heartbeat failed for ${task.taskId}")
        }

    (IO.sleep(heartbeatInterval) *> oneBeat).foreverM
  }

  private def withVersionCheck(taskId: String, operation: String)(db: DB[Boolean]): IO[Unit] =
    transactor.inSession(db).flatMap {
      case true  => IO.unit
      case false => logger.warn(s"$taskId: $operation affected 0 rows (version mismatch, likely recovered by dead execution handler)")
    }

  private def extractPayload[A](result: Unit | A | Schedule.NextAt[A]): Option[A] = result match {
    case ()                                    => None
    case nextAt: Schedule.NextAt[A @unchecked] => Some(nextAt.payload)
    case payload: A @unchecked                 => Some(payload)
  }

  private def onSuccess[A](
    repository: SchedulerRepository,
    task: Task[A],
    result: Unit | A | Schedule.NextAt[A],
    meters: SchedulerMeters
  ): IO[Unit] = {
    val taskId = task.taskId

    meters.executions.inc(Attribute("task.name", task.descriptor.taskName), Attribute("outcome", "success")) *> Clock[IO].realTimeInstant.flatMap {
      now =>
        task.schedule match {
          case _: Once =>
            logger.info(s"$taskId completed, removing") *>
              withVersionCheck(taskId, "remove")(repository.remove(task))
          case Schedule.RecurringWithFixedRate(every, _) =>
            val anchor    = task.scheduledAt.getOrElse(now)
            val candidate = anchor.plusMillis(every.toMillis)
            val next      = if (candidate.isBefore(now)) now.plusMillis(every.toMillis) else candidate
            logger.info(s"$taskId completed, next at $next") *>
              withVersionCheck(taskId, "reschedule")(repository.reschedule(task, next, extractPayload(result)))
          case Schedule.RecurringWithFixedDelay(after, _) =>
            val next = now.plusMillis(after.toMillis)
            logger.info(s"$taskId completed, next at $next") *>
              withVersionCheck(taskId, "reschedule")(repository.reschedule(task, next, extractPayload(result)))
          case Schedule.Cron(expression) =>
            expression.nextFrom(now) match {
              case Some(next) =>
                logger.info(s"$taskId completed, next at $next") *>
                  withVersionCheck(taskId, "reschedule")(repository.reschedule(task, next, extractPayload(result)))
              case None =>
                logger.error(s"$taskId: cron expression '$expression' failed to parse or has no future match, removing") *>
                  withVersionCheck(taskId, "remove")(repository.remove(task))
            }
          case Schedule.NextAt(_, _) =>
            result match {
              case nextAt: Schedule.NextAt[A @unchecked] =>
                logger.info(s"$taskId completed, next at ${nextAt.at}") *>
                  withVersionCheck(taskId, "reschedule")(repository.reschedule(task, nextAt.at, Some(nextAt.payload)))
              case _ =>
                logger.info(s"$taskId completed (custom, no continuation), removing") *>
                  withVersionCheck(taskId, "remove")(repository.remove(task))
            }
        }
    }
  }

  private def onFailure[A](
    repository: SchedulerRepository,
    task: Task[A],
    error: Throwable,
    meters: SchedulerMeters
  ): IO[Unit] = {
    val taskId     = task.taskId
    val previous   = task.consecutiveFailures.getOrElse(0)
    val failures   = previous + 1
    val shouldDrop = task.maxRetries.orElse(maxRetries).exists(failures > _)
    val outcome    = if (shouldDrop) "retries_exhausted" else "failure"

    meters.executions.inc(Attribute("task.name", task.descriptor.taskName), Attribute("outcome", outcome)) *> {
      if (shouldDrop) {
        logger.error(error)(s"$taskId exceeded max retries ($failures), abandoning") *>
          transactor
            .inTransaction {
              repository.remove(task).flatMap {
                case true  => task.onAbandon.fold(IO.unit)(_(task))
                case false =>
                  logger.warn(s"$taskId: remove affected 0 rows (version mismatch, likely recovered by dead execution handler); skipping onAbandon")
              }
            }
            .handleErrorWith(e => logger.error(e)(s"$taskId: abandon failed, leaving task in place for retry"))
      } else {
        Clock[IO].realTimeInstant.flatMap { now =>
          val backoffMs = math.min((retryBaseDelay.toMillis * math.pow(retryBackoffRate, previous.toDouble)).toLong, retryMaxDelay.toMillis)
          val retryAt   = now.plusMillis(backoffMs)
          logger.error(error)(s"$taskId failed (attempt $failures), retrying at $retryAt") *>
            withVersionCheck(taskId, "markFailure")(repository.markFailure(task, retryAt, failures))
        }
      }
    }
  }

  private def executeAndComplete[A](
    repository: SchedulerRepository,
    task: Task[A],
    meters: SchedulerMeters
  ): IO[Unit] = {
    val taskId     = task.taskId
    val nameAttr   = Attribute("task.name", task.descriptor.taskName)
    val payload    = task.descriptor.encoder(task.payload).noSpaces
    val attributes = Seq(
      nameAttr,
      Attribute("task.instance", task.taskInstance),
      Attribute("task.version", task.version),
      Attribute("task.priority", task.priority.toLong),
      Attribute("task.payload", payload)
    ) ++ task.consecutiveFailures.map(f => Attribute("task.consecutive_failures", f.toLong))

    val heartbeat = Resource.make(heartbeatLoop(repository, task).start)(_.cancel)

    val traced = summon[TelemetryContext].tracer
      .span(s"scheduler.execute ${task.descriptor.taskName}", attributes)
      .use { span =>
        heartbeat.surround {
          task.execution(task).attempt.flatMap {
            case Right(result) =>
              onSuccess(repository, task, result, meters)
                .handleErrorWith { e =>
                  logger.error(e)(s"Failed to complete $taskId after success")
                }
            case Left(e) =>
              span.recordException(e) *>
                span.setStatus(StatusCode.Error, e.getMessage) *>
                onFailure(repository, task, e, meters)
                  .handleErrorWith { completionError =>
                    logger.error(completionError)(s"Failed to record failure for $taskId")
                  }
          }
        }
      }

    Resource
      .make(meters.inFlight.inc(nameAttr))(_ => meters.inFlight.dec(nameAttr))
      .surround(traced)
  }

  private def runTaskUsingReservedPermit(
    repository: SchedulerRepository,
    task: Task[?],
    sem: Semaphore[IO],
    meters: SchedulerMeters
  ): IO[Unit] = {
    executeAndComplete(repository, task, meters).guarantee(sem.release)
  }

  private def deadExecutionDetectionLoop(repository: SchedulerRepository, meters: SchedulerMeters) = {
    val deadThresholdMillis = heartbeatInterval.toMillis * missedHeartbeatLimit
    val detectionInterval   = heartbeatInterval * 2

    val sweep: IO[Unit] =
      summon[TelemetryContext].tracer.span("scheduler.sweep").use { span =>
        Clock[IO].realTimeInstant.flatMap { now =>
          val staleThreshold = now.minusMillis(deadThresholdMillis)
          transactor
            .inSession {
              repository.reviveStaleExecutions(staleThreshold)
            }
            .flatMap { revived =>
              span.addAttribute(Attribute("scheduler.revived", revived.toLong)) *> {
                if (revived > 0)
                  meters.revived.add(revived.toLong) *>
                    logger.warn(s"Revived $revived dead execution(s) with heartbeat older than $staleThreshold")
                else
                  IO.unit
              }
            }
        }
      }

    val oneCheck: IO[Unit] =
      sweep.handleErrorWith { e =>
        logger.error(e)("Error during dead execution detection")
      } *> IO.sleep(detectionInterval)

    oneCheck.foreverM.background
  }

  private def mainLoop(repository: SchedulerRepository, meters: SchedulerMeters): Resource[IO, Unit] =
    for {
      sem <- Resource.eval(Semaphore[IO](concurrency.toLong))
      sup <- Supervisor[IO]
      _   <- deadExecutionDetectionLoop(repository, meters)
      _   <- pollLoop(repository, sem, sup, meters)
    } yield ()

  private def pollLoop(
    repository: SchedulerRepository,
    sem: Semaphore[IO],
    sup: Supervisor[IO],
    meters: SchedulerMeters
  ) = {
    (for {
      reserved <- reservePermits(sem, concurrency)
      _        <- if (reserved == 0) {
             IO.sleep(pollingInterval)
           } else {
             summon[TelemetryContext].tracer
               .noopScope(transactor.inSession(repository.pickAndMark(reserved)))
               .recoverWith { case t =>
                 logger.error(t)("Error while picking tasks") *>
                   IO.pure((Nil, Nil))
               }
               .flatMap { case (tasks, unreconstructable) =>
                 val logUnreconstructable =
                   if (unreconstructable.nonEmpty)
                     meters.unreconstructable.add(unreconstructable.size.toLong) *>
                       logger.error(s"Failed to reconstruct tasks (decode error or unknown), deleted: ${unreconstructable.mkString(", ")}")
                   else IO.unit
                 val used   = tasks.size
                 val unused = reserved - used

                 val startWorkers =
                   tasks.traverse_ { task =>
                     sup.supervise(runTaskUsingReservedPermit(repository, task, sem, meters)).void
                   }

                 val releaseSurplus =
                   if (unused > 0) sem.releaseN(unused.toLong) else IO.unit

                 val sleepIfIdle =
                   if (used >= reserved) IO.unit else IO.sleep(pollingInterval)

                 logUnreconstructable *> startWorkers *> releaseSurplus *> sleepIfIdle
               }
           }
    } yield ()).foreverM.background
  }
}

private final case class SchedulerMeters(
  executions: Counter[IO, Long],
  inFlight: UpDownCounter[IO, Long],
  revived: Counter[IO, Long],
  unreconstructable: Counter[IO, Long])

private object SchedulerMeters {
  def create(meter: Meter[IO]): IO[SchedulerMeters] =
    for {
      executions        <- meter.counter[Long]("scheduler.executions").create
      inFlight          <- meter.upDownCounter[Long]("scheduler.in_flight").create
      revived           <- meter.counter[Long]("scheduler.revived").create
      unreconstructable <- meter.counter[Long]("scheduler.unreconstructable").create
    } yield SchedulerMeters(executions, inFlight, revived, unreconstructable)
}
