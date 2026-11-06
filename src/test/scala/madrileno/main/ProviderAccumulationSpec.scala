package madrileno.main

import cats.effect.{IO, Resource}
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.events.outbox.{OutboxConfig, OutboxModule, OutboxSubscription, OutboxSubscriptionProvider, Reaction}
import madrileno.utils.featureflag.FeatureFlagModule
import madrileno.utils.http.ApplicationRouteProvider
import madrileno.utils.lifecycle.LifecycleProvider
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{ApplicationTaskProvider, Scheduler, SchedulerClient}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.data.{Identifier, Notification}

class ProviderAccumulationSpec extends AnyFunSpec with Matchers {

  private val stubTransactor: Transactor = new Transactor {
    override def inTransaction[A](f: DBInTransaction[A]): IO[A]         = IO.raiseError(new NotImplementedError())
    override def inSession[A](f: DB[A]): IO[A]                          = IO.raiseError(new NotImplementedError())
    override def notify(channel: Identifier, payload: String): IO[Unit] = IO.raiseError(new NotImplementedError())
    override def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]] =
      Resource.raiseError[IO, Stream[IO, Notification[String]], Throwable](new NotImplementedError())
  }

  // No DB: the lifecycle Resources are built but never run, so a stub transactor / in-memory bus suffice.
  private class LifecycleFixture(using TelemetryContext)
      extends ApplicationRouteProvider
      with ApplicationTaskProvider
      with LifecycleProvider
      with OutboxModule
      with FeatureFlagModule {
    lazy val telemetryContext: TelemetryContext = summon[TelemetryContext]
    val transactor: Transactor                  = stubTransactor
    val schedulerClient: SchedulerClient        = Scheduler(stubTransactor).client
    lazy val outboxConfig: OutboxConfig         = OutboxConfig()
    val cacheRuntime: CacheRuntime              = CacheRuntime.scaffeine
    val eventBusRuntime: EventBusRuntime        = EventBusRuntime.local
  }

  describe("ApplicationLoader lifecycles") {
    it("accumulate every LifecycleProvider module's contribution through trait linearization") {
      given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
      new LifecycleFixture().lifecycles should have size 2
    }
  }

  private def dummySubscription(consumer: String): OutboxSubscription =
    OutboxSubscription(consumer, s"evt-$consumer", (_, _) => IO.pure(Reaction.Drop("test")))

  private trait SubscribingModuleA extends OutboxSubscriptionProvider {
    override abstract def outboxSubscriptions: List[OutboxSubscription] = super.outboxSubscriptions :+ dummySubscription("a")
  }
  private trait SubscribingModuleB extends OutboxSubscriptionProvider {
    override abstract def outboxSubscriptions: List[OutboxSubscription] = super.outboxSubscriptions :+ dummySubscription("b")
  }

  describe("outbox subscriptions") {
    it("accumulate every contributing module's subscriptions through trait linearization") {
      (new SubscribingModuleA with SubscribingModuleB {}).outboxSubscriptions should have size 2
    }
  }
}
