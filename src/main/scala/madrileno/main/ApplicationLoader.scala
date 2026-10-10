package madrileno.main

import cats.effect.std.{SecureRandom, Supervisor}
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import com.comcast.ip4s.{Ipv4Address, Port}
import com.typesafe.config.Config
import madrileno.auction.AuctionModule
import madrileno.auth.AuthModule
import madrileno.healthcheck.HealthCheckModule
import madrileno.user.UserModule
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.events.outbox.{OutboxConfig, OutboxModule}
import madrileno.utils.featureflag.FeatureFlagModule
import madrileno.utils.featureflag.routers.FeatureFlagAdminRouter
import madrileno.utils.http.{ApplicationRouteProvider, Handlers, RateLimiterRuntime}
import madrileno.utils.lifecycle.LifecycleProvider
import madrileno.utils.mailer.{MailContext, MailPreviewProvider, MailPreviewRouter, Mailer, MailerConfig, SmtpSender}
import madrileno.utils.observability.*
import madrileno.utils.observability.admin.{ConfigAdminRouter, HeapdumpAdminRouter, LoggersAdminRouter, ThreaddumpAdminRouter}
import madrileno.utils.resilience.CircuitBreakerRuntime
import madrileno.utils.storage.{ObjectStore, ObjectStoreRuntime}
import madrileno.utils.task.{ApplicationTaskProvider, OneTimeTask, SchedulerAdminRouter, SchedulerClient}
import org.http4s.headers.`Content-Type`
import org.http4s.otel4s.middleware.instances.all.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{EntityEncoder, Headers, HttpRoutes, MediaType, Response, Status}
import org.typelevel.otel4s.Attribute
import pl.iterators.baklava.http4s.routes.BaklavaRoutes
import pl.iterators.stir.server.Route
import pl.iterators.stir.server.directives.{CredentialsHelper, RouteDirectives}
import pureconfig.*
import pureconfig.error.ConfigReaderException
import pureconfig.generic.derivation.EnumConfigReader
import pureconfig.module.ip4s.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.logging.{LogConfig, LogLevel, Logger, LoggingBackend}
import sttp.client4.opentelemetry.OpenTelemetryMetricsBackend
import sttp.client4.{WebSocketStreamBackend, logging}

import java.io.File
import java.net.URI
import scala.concurrent.duration.FiniteDuration

final case class HttpConfig(
  host: Ipv4Address,
  port: Port,
  maxRequestSize: Long,
  baseUrl: URI,
  shutdownTimeout: FiniteDuration)
    derives ConfigReader

enum Environment derives EnumConfigReader {
  case Dev, Test, Staging, Prod
}

final case class AppConfig(
  name: String,
  environment: Environment,
  version: String)
    derives ConfigReader
final case class AdminConfig(
  user: String,
  password: String,
  config: AdminInspectConfig)
    derives ConfigReader

final case class AdminInspectConfig(redactedPaths: Set[String]) derives ConfigReader

class ApplicationLoader(
  val config: ConfigObjectSource,
  httpBackend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  val transactor: Transactor,
  val clock: Clock[IO],
  val schedulerClient: SchedulerClient,
  val cacheRuntime: CacheRuntime,
  val rateLimiterRuntime: RateLimiterRuntime,
  val objectStoreRuntime: ObjectStoreRuntime,
  val eventBusRuntime: EventBusRuntime,
  val circuitBreakerRuntime: CircuitBreakerRuntime,
  val ioRuntime: IORuntime
)(using
  TelemetryContext,
  Supervisor[IO],
  SecureRandom[IO])
    extends ApplicationRouteProvider
    with ApplicationTaskProvider
    with LifecycleProvider
    with MailPreviewProvider
    with LoggingSupport
    with Handlers
    with AuthModule
    with UserModule
    with AuctionModule
    with FeatureFlagModule
    with HealthCheckModule
    with OutboxModule {
  lazy val httpConfig: HttpConfig              = config.at("http").loadOrThrow[HttpConfig]
  lazy val appConfig: AppConfig                = config.at("app").loadOrThrow[AppConfig]
  lazy val adminConfig: AdminConfig            = config.at("admin").loadOrThrow[AdminConfig]
  override lazy val outboxConfig: OutboxConfig = config.at("outbox").loadOrThrow[OutboxConfig]
  lazy val telemetryContext: TelemetryContext  = summon[TelemetryContext]
  lazy val supervisor: Supervisor[IO]          = summon[Supervisor[IO]]
  lazy val secureRandom: SecureRandom[IO]      = summon[SecureRandom[IO]]
  lazy val mailContext: MailContext            = MailContext(httpConfig.baseUrl)
  val objectStore: ObjectStore                 = objectStoreRuntime.objectStore

  protected lazy val mailerConfig: MailerConfig = config.at("mailer").loadOrThrow[MailerConfig]
  private lazy val smtpSender                   = new SmtpSender(mailerConfig)
  lazy val mailer: Mailer                       = new Mailer(smtpSender, schedulerClient, mailContext)

  private lazy val baklavaHttpRoutes: HttpRoutes[IO] = BaklavaRoutes.routes()
  private val baklavaPathSegments: Set[String]       = Set("openapi", "swagger", "swagger-ui", "docs")
  private val openApiSpecFile: File                  = new File("target/baklava/openapi/openapi.yml")
  private val specNotGeneratedHtml: String           =
    """<!doctype html><meta charset="utf-8"><title>Swagger UI — not generated yet</title>
      |<body style="font-family:system-ui,sans-serif;max-width:40rem;margin:4rem auto;line-height:1.6">
      |<h1>OpenAPI spec not generated yet</h1>
      |<p>The OpenAPI document is produced by the baklava router specs. Run <code>sbt test</code> once
      |(or just the router specs: <code>sbt &quot;testOnly *RouterSpec&quot;</code>), then refresh this page.</p>
      |</body>""".stripMargin

  lazy val baklavaDocs: Route = httpRoutesOf {
    case req if req.uri.path.segments.headOption.exists(_.encoded == "swagger") =>
      IO.blocking(openApiSpecFile.exists()).flatMap {
        case true  => baklavaHttpRoutes.run(req).getOrElseF(IO.pure(Response[IO](Status.NotFound)))
        case false =>
          IO.pure(
            Response[IO](Status.Ok)
              .withEntity(specNotGeneratedHtml)(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
          )
      }
    case req if baklavaPathSegments.contains(req.uri.path.segments.headOption.fold("")(_.encoded)) =>
      baklavaHttpRoutes.run(req).getOrElseF(IO.pure(Response[IO](Status.NotFound)))
  }

  private val adminAuthenticator: CredentialsHelper => Option[String] = {
    case p @ CredentialsHelper.Provided(id) if id == adminConfig.user && p.verify(adminConfig.password) => Some(id)
    case _                                                                                              => None
  }

  private lazy val rawConfig: Config = config.config().fold(f => throw new ConfigReaderException[Config](f), identity)

  lazy val schedulerAdminRouter: SchedulerAdminRouter     = new SchedulerAdminRouter(recurringTasks, oneTimeTasks, customTasks, schedulerClient)
  lazy val loggersAdminRouter: LoggersAdminRouter         = new LoggersAdminRouter
  lazy val configAdminRouter: ConfigAdminRouter           = new ConfigAdminRouter(rawConfig, adminConfig.config.redactedPaths)
  lazy val threaddumpAdminRouter: ThreaddumpAdminRouter   = ThreaddumpAdminRouter(ioRuntime, rateLimiterRuntime)
  lazy val heapdumpAdminRouter: HeapdumpAdminRouter       = new HeapdumpAdminRouter(rateLimiterRuntime)
  lazy val featureFlagAdminRouter: FeatureFlagAdminRouter = new FeatureFlagAdminRouter(featureFlagService)

  lazy val adminRoutes: Route = pathPrefix("admin") {
    authenticateBasic(realm = "madrileno-admin", authenticator = adminAuthenticator) { adminUser =>
      adminRoute ~ schedulerAdminRouter.routes ~ loggersAdminRouter.routes ~ configAdminRouter.routes ~ threaddumpAdminRouter.routes ~ heapdumpAdminRouter.routes ~ featureFlagAdminRouter
        .routes(adminUser)
    }
  }

  override def oneTimeTasks: List[OneTimeTask[?]] = super.oneTimeTasks :+ mailer.sendMailTask

  private lazy val httpClientLogger: logging.Logger[IO] = new Logger[IO] {
    override def apply(
      level: LogLevel,
      message: => String,
      throwable: Option[Throwable],
      context: Map[String, Any]
    ): IO[Unit] = {
      val contextMapped = context.view.mapValues(_.toString).toMap
      level match {
        case LogLevel.Trace => throwable.fold(logger.trace(contextMapped)(message))(logger.trace(contextMapped, _)(message))
        case LogLevel.Debug => throwable.fold(logger.debug(contextMapped)(message))(logger.debug(contextMapped, _)(message))
        case LogLevel.Info  => throwable.fold(logger.info(contextMapped)(message))(logger.info(contextMapped, _)(message))
        case LogLevel.Warn  => throwable.fold(logger.warn(contextMapped)(message))(logger.warn(contextMapped, _)(message))
        case LogLevel.Error => throwable.fold(logger.error(contextMapped)(message))(logger.error(contextMapped, _)(message))
      }
    }
  }

  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    OpenTelemetryTracingBackend(
      OpenTelemetryMetricsBackend(
        LoggingBackend(httpBackend, httpClientLogger, LogConfig.Default.copy(logRequestBody = true, logResponseBody = true)),
        telemetryContext.underlying
      )
    )

  private lazy val logActionLevel: Int = config.at("logging.loglevel-request-response").loadOrThrow[Int]

  private def logActionFor(ctx: Map[String, String]): String => IO[Unit] = logActionLevel match {
    case 4 => logger.debug(ctx)(_)
    case 3 => logger.info(ctx)(_)
    case 2 => logger.warn(ctx)(_)
    case 1 => logger.error(ctx)(_)
    case _ => _ => IO.unit
  }

  def routes(wsb: WebSocketBuilder2[IO]): Route = {
    val ws = wsb.withOnNonWebSocketRequest(
      IO.pure(Response[IO](Status.UpgradeRequired).withEntity("Upgrade required for WebSocket communication.")(using EntityEncoder.stringEncoder))
    )
    onSuccess(traceFields) { initialCtx =>
      val logAction = logActionFor(initialCtx)
      logRequest(logAction = Some(logAction)) {
        handleExceptions(exceptionHandler(logResult(logAction = Some(logAction)))) {
          handleRejections(rejectionHandler(logResult(logAction = Some(logAction)))) {
            apiVersionPrefix {
              authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
                handleExceptions(exceptionHandler(logResult(logAction = Some(logAction)))) {
                  handleRejections(rejectionHandler(logResult(logAction = Some(logAction)))) {
                    onSuccess(telemetryContext.tracer.currentSpanOrNoop.flatMap(_.addAttribute(Attribute("app.user.id", auth.userId.toString)))) {
                      logResult(logAction = Some(logAction)) {
                        onSuccess(telemetryContext.tracer.propagate(Headers.empty)) { newHeaders =>
                          mapResponseHeaders(_ ++ newHeaders) {
                            route(auth) ~ route ~ wsRoutes(auth, ws) ~ wsRoutes(ws)
                          }
                        }
                      }
                    }
                  }
                }
              } ~
                logResult(logAction = Some(logAction)) {
                  onSuccess(telemetryContext.tracer.propagate(Headers.empty)) { newHeaders =>
                    mapResponseHeaders(_ ++ newHeaders) {
                      route ~ wsRoutes(ws)
                    }
                  }
                }
            } ~ adminRoutes ~ (if (appConfig.environment == Environment.Dev) new MailPreviewRouter(mailPreviews, mailContext).routes ~ baklavaDocs
                               else RouteDirectives.reject)
          }
        }
      }
    }
  }
}
