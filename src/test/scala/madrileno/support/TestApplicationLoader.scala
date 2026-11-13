package madrileno.support

import cats.effect.std.{SecureRandom, Supervisor}
import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.gateways.VivinoGateway
import madrileno.auth.domain.{AuthContext, Provider, VerifiedExternalToken}
import madrileno.auth.services.{AuthVerifiers, DevAuthVerifier, JwtService}
import madrileno.healthcheck.services.ReadinessConfig
import madrileno.main.ApplicationLoader
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.events.outbox.OutboxConfig
import madrileno.utils.http.RateLimiterRuntime
import madrileno.utils.mailer.MailerConfig
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.resilience.CircuitBreakerRuntime
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pureconfig.ConfigSource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.net.ServerSocket
import java.time.Instant

trait TestApplicationLoader extends TestDatabase with BeforeAndAfterAll { self: Suite =>

  private val applicationResources = new SuiteResources

  given TracerProvider[IO] = TracerProvider.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given Tracer[IO]         = Tracer.noop[IO]
  given Meter[IO]          = Meter.noop[IO]
  given TelemetryContext   = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  given Supervisor[IO]     = applicationResources.allocate(Supervisor[IO])
  given SecureRandom[IO]   = TestGivens.secureRandom

  // Stable across the spec lifetime so tests can seed users matching the fake firebase verifier
  val firebaseToken: VerifiedExternalToken = TestData.verifiedExternalToken()
  val oidcToken: VerifiedExternalToken     = TestData.verifiedExternalToken(provider = Provider("test-oidc"))

  protected def rateLimiterRuntime: RateLimiterRuntime = TestRateLimiterRuntime.unbounded

  protected def testPgConfig(config: PgConfig): PgConfig = config

  protected def testReadinessConfig: ReadinessConfig = ReadinessConfig()

  protected def testAuthVerifiers: AuthVerifiers =
    AuthVerifiers(
      Map(Provider.Firebase -> FakeAuthVerifier(firebaseToken), Provider("test-oidc") -> FakeAuthVerifier(oidcToken), Provider.Dev -> DevAuthVerifier)
    )

  protected def testMailerConfig: MailerConfig = {
    val socket = new ServerSocket(0)
    val port   =
      try socket.getLocalPort
      finally socket.close()
    MailerConfig(host = "localhost", port = port, fromAddress = "test@example.com", tls = false)
  }

  lazy val application: ApplicationLoader = {
    val pgConfig        = testPgConfig(databaseConfig)
    val transactor      = applicationResources.allocate(PgTransactor.resource(pgConfig))
    val httpClient      = applicationResources.allocate(HttpClientFs2Backend.resource[IO]())
    val config          = ConfigSource.default
    val schedulerConfig = SchedulerConfig()
    val scheduler       = Scheduler(transactor, schedulerConfig)
    new ApplicationLoader(
      config,
      httpClient,
      transactor,
      Clock[IO],
      scheduler.client,
      TestCacheRuntime.unbounded,
      rateLimiterRuntime,
      TestObjectStoreRuntime.inMemory,
      EventBusRuntime.local,
      CircuitBreakerRuntime.default,
      IORuntime.global
    ) {
      override lazy val outboxConfig: OutboxConfig                     = OutboxConfig()
      override lazy val readinessConfig: ReadinessConfig               = testReadinessConfig
      override protected lazy val externalAuthVerifiers: AuthVerifiers = testAuthVerifiers
      // scripts:auction-block-start
      override protected lazy val vivinoGateway: VivinoGateway = (_, _) => IO.pure(None)
      // scripts:auction-block-end
      override protected lazy val mailerConfig: MailerConfig = testMailerConfig
    }
  }

  override protected def afterAll(): Unit =
    try applicationResources.releaseAll()
    finally super.afterAll()

  private val config                             = ConfigSource.default
  private val jwtConfig: JwtService.Config       = config.at("jwt").loadOrThrow[JwtService.Config]
  val jwtService: JwtService                     = JwtService(jwtConfig)
  def validJwt(authContext: AuthContext): String = jwtService.encode(authContext, Instant.now()).unwrap
}
