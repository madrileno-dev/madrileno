package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO, Ref}
import io.circe.{Codec, Decoder, Encoder}
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class SchedulerOnAbandonSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext    = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private given Codec[Unit] = Codec.from(Decoder.decodeUnit, Encoder.encodeUnit)

  private val config = SchedulerConfig(pollingInterval = 100.millis, retryBaseDelay = 100.millis, maxRetries = None)

  private def waitFor(
    ref: Ref[IO, Int],
    target: Int,
    timeout: FiniteDuration = 8.seconds
  ): IO[Int] = {
    def poll(remaining: FiniteDuration): IO[Int] =
      ref.get.flatMap { v =>
        if (v >= target) IO.pure(v)
        else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException(s"timed out at $v, wanted >= $target"))
        else IO.sleep(50.millis) *> poll(remaining - 50.millis)
      }
    poll(timeout)
  }

  "Scheduler onAbandon" should {
    "run onAbandon and remove the task when the per-task maxRetries is exhausted" in {
      for {
        attempts  <- Ref.of[IO, Int](0)
        abandoned <- Ref.of[IO, Int](0)
        descriptor = TaskDescriptor[Unit]("test-onabandon")
        task       = OneTimeTask[Unit](descriptor, _ => attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("always fails")))
                 .copy(maxRetries = Some(1), onAbandon = Some(_ => abandoned.update(_ + 1)))
        scheduler = Scheduler(transactor, config)
        client    = scheduler.client
        finalAttempts <- scheduler.run(recurringTasks = Nil, oneTimeTasks = List(task), customTasks = Nil).use { _ =>
                           for {
                             _         <- client.schedule(task.instance("run-1", ()))
                             _         <- waitFor(abandoned, 1)
                             a         <- attempts.get
                             remaining <- client.listTasks
                           } yield (a, remaining.exists(r => r.taskName == "test-onabandon" && r.taskInstance == "run-1"))
                         }
      } yield {
        finalAttempts._1 shouldBe 2 // initial attempt + 1 retry, then abandoned
        finalAttempts._2 shouldBe false // task row removed after abandon
      }
    }

    "abandoning a custom task dead-letters and terminates its chain permanently" in {
      for {
        attempts  <- Ref.of[IO, Int](0)
        abandoned <- Ref.of[IO, Int](0)
        descriptor = TaskDescriptor[Unit]("test-onabandon-custom")
        task       = CustomTask[Unit](descriptor, _ => attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("always fails")))
                 .copy(maxRetries = Some(0), onAbandon = Some(_ => abandoned.update(_ + 1)))
        scheduler = Scheduler(transactor, config)
        client    = scheduler.client
        result <- scheduler.run(recurringTasks = Nil, oneTimeTasks = Nil, customTasks = List(task)).use { _ =>
                    for {
                      now       <- Clock[IO].realTimeInstant
                      _         <- client.schedule(task.instance("chain-1", (), firstAt = now))
                      _         <- waitFor(abandoned, 1)
                      a         <- attempts.get
                      remaining <- client.listTasks
                    } yield (a, remaining.exists(r => r.taskName == "test-onabandon-custom" && r.taskInstance == "chain-1"))
                  }
      } yield {
        result._1 shouldBe 1
        result._2 shouldBe false // chain row removed: the recurring chain is permanently terminated
      }
    }

    "keep the task row when onAbandon raises and the abandon transaction rolls back" in {
      for {
        abandoned <- Ref.of[IO, Int](0)
        descriptor = TaskDescriptor[Unit]("test-onabandon-rollback")
        task       = OneTimeTask[Unit](descriptor, _ => IO.raiseError(new RuntimeException("always fails")))
                 .copy(maxRetries = Some(0), onAbandon = Some(_ => abandoned.update(_ + 1) *> IO.raiseError(new RuntimeException("abandon boom"))))
        scheduler = Scheduler(transactor, config)
        client    = scheduler.client
        result <- scheduler.run(recurringTasks = Nil, oneTimeTasks = List(task), customTasks = Nil).use { _ =>
                    for {
                      _         <- client.schedule(task.instance("run-rollback", ()))
                      a         <- waitFor(abandoned, 1)
                      remaining <- client.listTasks
                    } yield (a, remaining.exists(r => r.taskName == "test-onabandon-rollback" && r.taskInstance == "run-rollback"))
                  }
      } yield {
        result._1 should be >= 1
        result._2 shouldBe true
      }
    }
  }
}
