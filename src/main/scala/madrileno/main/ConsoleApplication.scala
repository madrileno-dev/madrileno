package madrileno.main

import cats.effect.std.{SecureRandom, Supervisor}
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.http.RateLimiterRuntime
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.resilience.CircuitBreakerRuntime
import madrileno.utils.storage.{ObjectStoreRuntime, StorageConfig}
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import pureconfig.ConfigSource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

// Boots an `ApplicationLoader` synchronously for `./scripts/dev-console.scala`. Dev-only.
object ConsoleApplication {
  def boot(): ApplicationLoader = {
    val config    = ConfigSource.default
    val appConfig = config.at("app").loadOrThrow[AppConfig]

    require(
      appConfig.environment == Environment.Dev,
      s"ConsoleApplication refuses to boot with app.environment=${appConfig.environment}. Set APP_ENVIRONMENT=dev."
    )

    import org.typelevel.otel4s.metrics.Meter.Implicits.noop as meterNoop
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop as tracerNoop
    given TelemetryContext   = TelemetryContext(meterNoop, tracerNoop, OpenTelemetry.noop())
    given MeterProvider[IO]  = MeterProvider.noop[IO]
    given TracerProvider[IO] = TracerProvider.noop[IO]

    val pgConfig        = config.at("pg").loadOrThrow[PgConfig]
    val schedulerConfig = config.at("scheduler").loadOrThrow[SchedulerConfig]
    val storageConfig   = config.at("storage").loadOrThrow[StorageConfig]

    val program: Resource[IO, ApplicationLoader] = for {
      httpClient             <- HttpClientFs2Backend.resource[IO]()
      transactor             <- PgTransactor.resource(pgConfig)
      objectStoreRuntime     <- ObjectStoreRuntime.s3(storageConfig)
      given Supervisor[IO]   <- Supervisor[IO]
      given SecureRandom[IO] <- Resource.eval(SecureRandom.javaSecuritySecureRandom[IO])
      application = {
        val scheduler             = Scheduler(transactor, schedulerConfig)
        val cacheRuntime          = CacheRuntime.scaffeine
        val rateLimiterRuntime    = RateLimiterRuntime.scaffeine()
        val eventBusRuntime       = EventBusRuntime.postgres(transactor)
        val circuitBreakerRuntime = CircuitBreakerRuntime.default
        ApplicationLoader(
          config,
          httpClient,
          transactor,
          Clock[IO],
          scheduler.client,
          cacheRuntime,
          rateLimiterRuntime,
          objectStoreRuntime,
          eventBusRuntime,
          circuitBreakerRuntime,
          IORuntime.global
        )
      }
      // Without this the console never subscribes to invalidations — it would serve cache-stale reads until TTL.
      _ <- application.lifecycles.sequence_
    } yield application

    // Single allocation, single release. Shutdown hook runs the release on JVM exit
    // so connections / supervisors close cleanly.
    val (app, release) = program.allocated.unsafeRunSync()
    val _              = sys.addShutdownHook {
      try release.unsafeRunSync()
      catch { case _: Throwable => () }
    }
    app
  }
}
