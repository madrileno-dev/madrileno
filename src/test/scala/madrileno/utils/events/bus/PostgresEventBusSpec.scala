package madrileno.utils.events.bus

import cats.effect.std.Supervisor
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import madrileno.utils.events.EventCodec
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.data.{Identifier, Notification}

import scala.concurrent.duration.*

class PostgresEventBusSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private final case class Sample(id: Int, name: String) derives EventCodec

  // Unique per-test channel names so concurrent test runs against the same Postgres can't cross-talk.
  private def channel(prefix: String): String = s"${prefix}_${TestData.randomUuid().toString.replace("-", "_")}"

  private val testTimeout: FiniteDuration = 10.seconds

  "EventBusRuntime.postgres" should {
    "deliver events across separate runtime instances over the same Postgres" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val name             = channel("eventbus_crossinst_test")
        val publisherBus     = EventBusRuntime.postgres(transactor).topic[Sample](name, maxQueued = 64)
        val subscriberBus    = EventBusRuntime.postgres(transactor).topic[Sample](name, maxQueued = 64)
        subscriberBus.subscribeAwait
          .use { stream =>
            for {
              sub <- stream.take(1).compile.lastOrError.start
              _   <- publisherBus.publish(Sample(1, "hello"))
              got <- sub.joinWithNever
            } yield got shouldBe Sample(1, "hello")
          }
          .timeout(testTimeout)
      }
    }

    "fan out a publish to multiple subscribers on a single runtime instance" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val bus              = EventBusRuntime.postgres(transactor).topic[Sample](channel("eventbus_fanout_test"), maxQueued = 64)
        (bus.subscribeAwait, bus.subscribeAwait).tupled
          .use { case (sa, sb) =>
            for {
              a  <- sa.take(1).compile.lastOrError.start
              b  <- sb.take(1).compile.lastOrError.start
              _  <- bus.publish(Sample(42, "broadcast"))
              ra <- a.joinWithNever
              rb <- b.joinWithNever
            } yield {
              ra shouldBe Sample(42, "broadcast")
              rb shouldBe Sample(42, "broadcast")
            }
          }
          .timeout(testTimeout)
      }
    }
  }
}

class PostgresEventBusResilienceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private final case class Sample(id: Int, name: String) derives EventCodec

  private def channel(prefix: String): String = s"${prefix}_${TestData.randomUuid().toString.replace("-", "_")}"

  private val testTimeout: FiniteDuration = 15.seconds

  private final class FlakyListenTransactor(delegate: Transactor, failures: Ref[IO, Int]) extends Transactor {
    override def inTransaction[A](f: DBInTransaction[A]): IO[A]                                              = delegate.inTransaction(f)
    override def inSession[A](f: DB[A]): IO[A]                                                               = delegate.inSession(f)
    override def notify(channel: Identifier, payload: String): IO[Unit]                                      = delegate.notify(channel, payload)
    override def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] =
      Resource.eval(failures.modify(n => (n - 1, n))).flatMap { remaining =>
        if (remaining > 0) Resource.eval(IO.raiseError(new RuntimeException("simulated LISTEN failure")))
        else delegate.listen(channel, maxQueued)
      }
  }

  "EventBusRuntime.postgres" should {
    "skip a notification it cannot decode and keep delivering later events" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val name             = channel("eventbus_undecodable_test")
        val identifier       = Identifier.fromValue(name).toOption.getOrElse(fail(s"bad channel $name"))
        val bus              = EventBusRuntime.postgres(transactor).topic[Sample](name, maxQueued = 64)
        bus.subscribeAwait
          .use { stream =>
            for {
              sub <- stream.take(1).compile.lastOrError.start
              _   <- transactor.notify(identifier, "this is not json")
              _   <- transactor.notify(identifier, """{"id":"wrong type","name":1}""")
              _   <- bus.publish(Sample(3, "after-garbage"))
              got <- sub.joinWithNever
            } yield got shouldBe Sample(3, "after-garbage")
          }
          .timeout(testTimeout)
      }
    }

    "re-establish LISTEN after the connection drops" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        for {
          failures <- Ref.of[IO, Int](1)
          flaky = new FlakyListenTransactor(transactor, failures)
          bus   = EventBusRuntime.postgres(flaky).topic[Sample](channel("eventbus_reconnect_test"), maxQueued = 64)
          got <- bus.subscribeAwait
                   .use { stream =>
                     for {
                       sub <- stream.take(1).compile.lastOrError.start
                       _   <- bus.publish(Sample(9, "after-reconnect"))
                       got <- sub.joinWithNever
                     } yield got
                   }
                   .timeout(testTimeout)
          attempts <- failures.get
        } yield {
          got shouldBe Sample(9, "after-reconnect")
          attempts should be < 0
        }
      }
    }
  }
}
