package madrileno.support

import cats.effect.std.{SecureRandom, Supervisor}
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, FiberSnapshot, IO}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import madrileno.auction.gateways.VivinoGateway
import madrileno.auth.domain.{AuthContext, Provider, VerifiedExternalToken}
import madrileno.auth.services.{AuthVerifiers, DevAuthVerifier, JwtService}
import madrileno.main.ApplicationLoader
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.events.outbox.OutboxConfig
import madrileno.utils.http.RateLimiterRuntime
import madrileno.utils.mailer.MailerConfig
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.observability.admin.ThreaddumpAdminRouter
import madrileno.utils.resilience.CircuitBreakerRuntime
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.flywaydb.core.Flyway
import org.scalatest.Suite
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.time.Instant

trait TestApplicationLoader extends TestContainersForAll with TestMailpit { self: Suite =>

  override type Containers = PostgreSQLContainer

  override def startContainers(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def().start()
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
    container
  }

  given Tracer[IO]       = Tracer.noop[IO]
  given Meter[IO]        = Meter.noop[IO]
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())
  // Finalizer discarded — supervised fibers live for the spec's JVM lifetime, same rationale as the pool below.
  given Supervisor[IO]   = Supervisor[IO].allocated.unsafeRunSync()._1
  given SecureRandom[IO] = TestGivens.secureRandom

  // Stable across the spec lifetime so tests can seed users matching the fake firebase verifier
  val firebaseToken: VerifiedExternalToken = TestData.verifiedExternalToken()
  val oidcToken: VerifiedExternalToken     = TestData.verifiedExternalToken(provider = Provider("test-oidc"))

  protected def rateLimiterRuntime: RateLimiterRuntime = TestRateLimiterRuntime.unbounded

  protected def takeFiberSnapshot: IO[FiberSnapshot] = IO.blocking(IORuntime.global.liveFiberSnapshot())

  protected def testAuthVerifiers: AuthVerifiers =
    AuthVerifiers(
      Map(Provider.Firebase -> FakeAuthVerifier(firebaseToken), Provider("test-oidc") -> FakeAuthVerifier(oidcToken), Provider.Dev -> DevAuthVerifier)
    )

  lazy val application: ApplicationLoader = withContainers { container =>
    val pgConfig = PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = container.databaseName,
      password = Some(container.password)
    )
    // Finalizers discarded — pool and HTTP client lifetime is tied to the Testcontainers PG container.
    // Releasing in afterAll causes broken-pipe errors because ScalaTest's afterAll ordering
    // conflicts with TestContainersForAll's container lifecycle.
    val transactor      = PgTransactor.resource(pgConfig).allocated.unsafeRunSync()._1
    val httpClient      = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()._1
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
      override protected lazy val externalAuthVerifiers: AuthVerifiers = testAuthVerifiers
      override lazy val threaddumpAdminRouter: ThreaddumpAdminRouter   = new ThreaddumpAdminRouter(takeFiberSnapshot, self.rateLimiterRuntime)
      // scripts:auction-block-start
      override protected lazy val vivinoGateway: VivinoGateway = (_, _) => IO.pure(None)
      // scripts:auction-block-end
      override protected lazy val mailerConfig: MailerConfig =
        MailerConfig(host = mailpitHost, port = mailpitSmtpPort, fromAddress = "test@example.com", tls = false)
    }
  }

  private val config                             = ConfigSource.default
  private val jwtConfig: JwtService.Config       = config.at("jwt").loadOrThrow[JwtService.Config]
  val jwtService: JwtService                     = JwtService(jwtConfig)
  def validJwt(authContext: AuthContext): String = jwtService.encode(authContext, Instant.now()).unwrap
}
