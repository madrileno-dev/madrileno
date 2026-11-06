package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Ref}
import io.circe.Json
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.implicits.*

import java.time.Instant
import scala.concurrent.duration.*

class SchedulerRecoverySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val config =
    SchedulerConfig(
      pollingInterval = 100.millis,
      heartbeatInterval = 100.millis,
      missedHeartbeatLimit = 1,
      retryBaseDelay = 100.millis,
      maxRetries = Some(0)
    )

  private def insertRow(row: TaskRow): IO[Unit] = withSession {
    val table = TaskRowTable
    summon[Session[IO]].execute(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c})".command)(row).void
  }

  private def rowFor(
    taskName: String,
    instance: String,
    data: Json,
    nextExecution: Instant,
    picked: Boolean = false,
    lastHeartbeat: Option[Instant] = None
  ): TaskRow =
    TaskRow(
      taskName = taskName,
      taskInstance = instance,
      taskData = data,
      nextExecution = nextExecution,
      picked = picked,
      pickedBy = Option.when(picked)("dead-node"),
      lastSuccess = None,
      lastFailure = None,
      consecutiveFailures = None,
      lastHeartbeat = lastHeartbeat,
      version = 0L,
      priority = 0,
      traceParent = None
    )

  private def waitUntil(condition: IO[Boolean], timeout: FiniteDuration = 8.seconds): IO[Unit] = {
    def poll(remaining: FiniteDuration): IO[Unit] =
      condition.flatMap {
        case true                           => IO.unit
        case false if remaining <= 0.millis => IO.raiseError(new RuntimeException("condition not met in time"))
        case false                          => IO.sleep(50.millis) *> poll(remaining - 50.millis)
      }
    poll(timeout)
  }

  private def rowPresent(
    client: SchedulerClient,
    taskName: String,
    instance: String
  ): IO[Boolean] =
    client.listTasks.map(_.exists(r => r.taskName == taskName && r.taskInstance == instance))

  "Scheduler" should {
    "delete rows it cannot reconstruct and still run the well-formed ones" in {
      for {
        executed <- Ref.of[IO, List[Int]](Nil)
        descriptor = TaskDescriptor[Int]("recovery-int-task")
        task       = Task.oneTime(descriptor)(t => executed.update(_ :+ t.payload))
        now <- Clock[IO].realTimeInstant
        _   <- insertRow(rowFor("recovery-no-such-task", "orphan", Json.obj(), now))
        _   <- insertRow(rowFor(descriptor.taskName, "garbage-payload", Json.fromString("not an int"), now))
        _   <- insertRow(rowFor(descriptor.taskName, "good-payload", Json.fromInt(7), now))
        scheduler = Scheduler(transactor, config.copy(heartbeatInterval = 1.second, missedHeartbeatLimit = 6))
        client    = scheduler.client
        outcome <- scheduler.run(oneTimeTasks = List(task)).use { _ =>
                     for {
                       _        <- waitUntil(executed.get.map(_.contains(7)))
                       _        <- waitUntil(rowPresent(client, "recovery-no-such-task", "orphan").map(!_))
                       _        <- waitUntil(rowPresent(client, descriptor.taskName, "garbage-payload").map(!_))
                       _        <- waitUntil(rowPresent(client, descriptor.taskName, "good-payload").map(!_))
                       payloads <- executed.get
                     } yield payloads
                   }
      } yield outcome shouldBe List(7)
    }

    "revive an execution whose owner stopped sending heartbeats" in {
      for {
        runs <- Ref.of[IO, Int](0)
        descriptor = TaskDescriptor[Unit]("recovery-dead-execution")
        task       = Task.oneTime(descriptor)(_ => runs.update(_ + 1))
        now <- Clock[IO].realTimeInstant
        _   <- insertRow(rowFor(descriptor.taskName, "stuck", Json.obj(), now, picked = true, lastHeartbeat = Some(now.minusSeconds(3600))))
        scheduler = Scheduler(transactor, config)
        client    = scheduler.client
        outcome <- scheduler.run(oneTimeTasks = List(task)).use { _ =>
                     for {
                       _    <- waitUntil(runs.get.map(_ >= 1))
                       _    <- waitUntil(rowPresent(client, descriptor.taskName, "stuck").map(!_))
                       seen <- runs.get
                     } yield seen
                   }
      } yield outcome shouldBe 1
    }

    "revive an execution that was picked but never heartbeated at all" in {
      for {
        runs <- Ref.of[IO, Int](0)
        descriptor = TaskDescriptor[Unit]("recovery-never-beat")
        task       = Task.oneTime(descriptor)(_ => runs.update(_ + 1))
        now <- Clock[IO].realTimeInstant
        _   <- insertRow(rowFor(descriptor.taskName, "silent", Json.obj(), now, picked = true, lastHeartbeat = None))
        scheduler = Scheduler(transactor, config)
        outcome <- scheduler.run(oneTimeTasks = List(task)).use(_ => waitUntil(runs.get.map(_ >= 1)) *> runs.get)
      } yield outcome shouldBe 1
    }
  }
}
