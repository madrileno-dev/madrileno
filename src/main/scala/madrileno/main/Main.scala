package madrileno.main

import cats.effect.std.{SecureRandom, Supervisor}
import cats.effect.{Clock, IO, IOApp, Resource}
import cats.syntax.all.*
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.Migrations
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.http.{Cors, CorsConfig, RateLimiterRuntime}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.resilience.CircuitBreakerRuntime
import madrileno.utils.storage.{ObjectStoreRuntime, StorageConfig}
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.http4s.RequestPrelude
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.http4s.otel4s.middleware.trace.redact.{HeaderRedactor, PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{ServerMiddleware, ServerSpanDataProvider}
import org.http4s.server.middleware.{EntityLimiter, Metrics}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pl.iterators.stir.server.ToHttpRoutes
import pureconfig.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

object Main extends IOApp.Simple with LoggingSupport {
  override def run: IO[Unit] =
    (for {
      config    <- Resource.eval(IO.delay(ConfigSource.default))
      appConfig <- Resource.eval(IO.delay(config.at("app").loadOrThrow[AppConfig]))
      otel      <- OtelJava.autoConfigured[IO]()
      given TracerProvider[IO] = otel.tracerProvider
      given Tracer[IO] <- Resource.eval(summon[TracerProvider[IO]].get(appConfig.name))
      given MeterProvider[IO] = otel.meterProvider
      given Meter[IO] <- Resource.eval(summon[MeterProvider[IO]].get(appConfig.name))
      given TelemetryContext = TelemetryContext(Meter[IO], Tracer[IO], otel.underlying)
      _                      = OpenTelemetryAppender.install(otel.underlying)
      httpClient <- HttpClientFs2Backend.resource[IO]()
      pgConfig   <- Resource.eval(IO.delay(config.at("pg").loadOrThrow[PgConfig]))
      transactor <- PgTransactor.resource(pgConfig)
      _          <- Resource.eval(IO.whenA(appConfig.environment == Environment.Dev)(Migrations.warnIfPending(pgConfig)))
      clock = Clock[IO]
      schedulerConfig <- Resource.eval(IO.delay(config.at("scheduler").loadOrThrow[SchedulerConfig]))
      scheduler    = Scheduler(transactor, schedulerConfig)
      cacheRuntime = CacheRuntime.scaffeine
      trustedProxies <- Resource.eval(IO.delay(RateLimiterRuntime.parseTrustedProxies(config.at("rate-limit.trusted-proxies").loadOrThrow[String])))
      rateLimiterRuntime = RateLimiterRuntime.scaffeine(trustedProxies = trustedProxies)
      storageConfig          <- Resource.eval(IO.delay(config.at("storage").loadOrThrow[StorageConfig]))
      objectStoreRuntime     <- ObjectStoreRuntime.s3(storageConfig)
      given Supervisor[IO]   <- Supervisor[IO]
      given SecureRandom[IO] <- Resource.eval(SecureRandom.javaSecuritySecureRandom[IO])
      eventBusRuntime       = EventBusRuntime.postgres(transactor)
      circuitBreakerRuntime = CircuitBreakerRuntime.default
      application           =
        ApplicationLoader(
          config,
          httpClient,
          transactor,
          clock,
          scheduler.client,
          cacheRuntime,
          rateLimiterRuntime,
          objectStoreRuntime,
          eventBusRuntime,
          circuitBreakerRuntime,
          runtime
        )
      _ <- scheduler.run(recurringTasks = application.recurringTasks, oneTimeTasks = application.oneTimeTasks, customTasks = application.customTasks)
      _ <- application.lifecycles.sequence_
      metricsOps <- OtelMetrics.serverMetricsOps[IO]().toResource
      redactor        = new QueryRedactor.NeverRedact with PathRedactor.NeverRedact
      routeClassifier = new RouteClassifier {
                          override def classify(request: RequestPrelude): Option[String] = {
                            val classified = request.uri.path.segments.foldLeft("") { (acc, seg) =>
                              if (seg.encoded.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                                acc + "/{uuid}"
                              else if (seg.encoded.forall(_.isDigit)) acc + "/{id}"
                              else acc + s"/${seg.encoded}"
                            }
                            Some(classified)
                          }
                        }
      serverMiddleware <- ServerMiddleware
                            .builder[IO](
                              ServerSpanDataProvider
                                .openTelemetry(redactor)
                                .withRouteClassifier(routeClassifier)
                                .optIntoClientPort
                                .optIntoHttpRequestHeaders(HeaderRedactor.default)
                                .optIntoHttpResponseHeaders(HeaderRedactor.default)
                            )
                            .build
                            .toResource
      corsConfig <- Resource.eval(IO.delay(config.at("cors").loadOrThrow[CorsConfig]))
      corsPolicy <- Resource.eval(Cors.policy(corsConfig, appConfig.environment, application.httpConfig.baseUrl))
      _          <- EmberServerBuilder
             .default[IO]
             .withHost(application.httpConfig.host)
             .withPort(application.httpConfig.port)
             .withShutdownTimeout(application.httpConfig.shutdownTimeout)
             .withHttpWebSocketApp { wsb =>
               val httpApp = serverMiddleware.wrapHttpApp(
                 EntityLimiter.httpApp(Metrics(metricsOps)(application.routes(wsb).toHttpRoutes).orNotFound, application.httpConfig.maxRequestSize)
               )
               corsPolicy.fold(httpApp)(_(httpApp))
             }
             .build
    } yield application)
      .use { _ =>
        IO.never
      }
      .onError { case e => loggerWithoutTracing.error(e)("Application stopped with an error") }
}
