package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Ref}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.trace.data.SpanData
import madrileno.support.{RecordingTracer, TestTransactor}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class SchedulerIntegrationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val config = SchedulerConfig(pollingInterval = 100.millis, retryBaseDelay = 200.millis, maxRetries = Some(2))

  private def waitFor(
    ref: Ref[IO, Int],
    target: Int,
    timeout: FiniteDuration = 5.seconds
  ): IO[Int] = {
    def poll(remaining: FiniteDuration): IO[Int] =
      ref.get.flatMap { value =>
        if (value >= target) IO.pure(value)
        else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException(s"Timed out: got $value, wanted >= $target"))
        else IO.sleep(50.millis) *> poll(remaining - 50.millis)
      }
    poll(timeout)
  }

  "Scheduler" should {
    "execute one-time, recurring, retry, payload, custom, and transactional tasks" in {
      for {
        onceCounter      <- Ref.of[IO, Int](0)
        recurringCounter <- Ref.of[IO, Int](0)
        fixedRateCounter <- Ref.of[IO, Int](0)
        cronCounter      <- Ref.of[IO, Int](0)
        retryCounter     <- Ref.of[IO, Int](0)
        payloadRef       <- Ref.of[IO, Int](0)
        customPayloadRef <- Ref.of[IO, String]("")
        customCounter    <- Ref.of[IO, Int](0)
        txCounter        <- Ref.of[IO, Int](0)

        onceDescriptor = TaskDescriptor[Unit]("test-once")
        onceTask       = Task.oneTime(onceDescriptor) { _ => onceCounter.update(_ + 1) }

        recurringDelayTask = Task.recurring("test-recurring-delay", Schedule.RecurringWithFixedDelay(200.millis)) { _ =>
                               recurringCounter.update(_ + 1)
                             }

        fixedRateTask = Task.recurring("test-fixed-rate", Schedule.RecurringWithFixedRate(200.millis)) { _ =>
                          fixedRateCounter.update(_ + 1)
                        }

        cronTask = Task.recurring("test-cron", Schedule.Cron(CronExpression.unsafeParse("* * * ? * *"))) { _ =>
                     cronCounter.update(_ + 1)
                   }

        retryDescriptor = TaskDescriptor[Unit]("test-retry")
        retryTask       = Task.oneTime(retryDescriptor) { _ =>
                      retryCounter.update(_ + 1) *> IO.raiseError(new RuntimeException("Intentional failure"))
                    }

        payloadTask = Task.recurring[Int]("test-payload", Schedule.RecurringWithFixedDelay(200.millis), payload = 0) { t =>
                        val next = t.payload + 1
                        payloadRef.set(next).as(next)
                      }

        customDescriptor = TaskDescriptor[String]("test-custom")
        customTask       = Task.custom(customDescriptor) { payload =>
                       customPayloadRef.set(payload) *>
                         customCounter.updateAndGet(_ + 1).flatMap { count =>
                           if (count < 3)
                             Clock[IO].realTimeInstant.map(now => Schedule.NextAt(now.plusMillis(100), s"iteration-$count"))
                           else IO.unit
                         }
                     }

        txDescriptor = TaskDescriptor[Unit]("test-tx")
        txTask       = Task.oneTime(txDescriptor) { _ => txCounter.update(_ + 1) }

        scheduler = Scheduler(transactor, config)
        client    = scheduler.client

        results <-
          scheduler
            .run(
              recurringTasks = List(recurringDelayTask, fixedRateTask, cronTask, payloadTask),
              oneTimeTasks = List(onceTask, retryTask, txTask),
              customTasks = List(customTask)
            )
            .use { _ =>
              for {
                _   <- client.schedule(onceTask.instance("exec-once", ()))
                _   <- client.schedule(retryTask.instance("exec-retry", ()))
                now <- Clock[IO].realTimeInstant
                _   <- client.schedule(customTask.instance("exec-custom", "initial", now))
                _   <- transactor.inTransaction(client.scheduleTransactionally(txTask.instance("exec-tx", ())))

                onceResult      <- waitFor(onceCounter, 1)
                recurringResult <- waitFor(recurringCounter, 3)
                fixedRateResult <- waitFor(fixedRateCounter, 3)
                cronResult      <- waitFor(cronCounter, 2)
                retryResult     <- waitFor(retryCounter, 3, timeout = 10.seconds)
                payloadResult   <- waitFor(payloadRef, 3)
                customResult    <- waitFor(customCounter, 3, timeout = 10.seconds)
                txResult        <- waitFor(txCounter, 1)
                lastPayload     <- customPayloadRef.get
              } yield (onceResult, recurringResult, fixedRateResult, cronResult, retryResult, payloadResult, customResult, txResult, lastPayload)
            }
      } yield {
        val (onceResult, recurringResult, fixedRateResult, cronResult, retryResult, payloadResult, customResult, txResult, lastCustomPayload) =
          results
        onceResult shouldBe 1
        recurringResult should be >= 3
        fixedRateResult should be >= 3
        cronResult should be >= 2
        retryResult shouldBe 3
        payloadResult should be >= 3
        customResult shouldBe 3
        txResult shouldBe 1
        lastCustomPayload shouldBe "iteration-2"
      }
    }

    "wrap each stale-execution sweep in a scheduler.sweep root span carrying the revived count" in {
      def firstSweep(recorder: RecordingTracer, remaining: FiniteDuration): IO[List[SpanData]] =
        recorder.spansNamed("scheduler.sweep").flatMap { spans =>
          if (spans.nonEmpty) IO.pure(spans)
          else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException("Timed out waiting for scheduler.sweep span"))
          else IO.sleep(50.millis) *> firstSweep(recorder, remaining - 50.millis)
        }
      for {
        recorder <- RecordingTracer.create
        spans    <- Scheduler(transactor, config)(using Clock[IO], recorder.context).run().use(_ => firstSweep(recorder, 5.seconds))
      } yield {
        spans.map(_.getParentSpanContext.isValid) shouldBe List(false)
        spans.map(RecordingTracer.longAttribute(_, "scheduler.revived")) shouldBe List(Some(0L))
      }
    }
  }
}
