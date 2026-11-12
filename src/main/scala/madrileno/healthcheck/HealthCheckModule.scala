package madrileno.healthcheck

import cats.effect.{Clock, IO, Resource}
import com.softwaremill.macwire.*
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.{AdminHealthCheckRouter, HealthCheckRouter}
import madrileno.healthcheck.services.{AdminHealthCheckService, HealthCheckService, PostgresProbe, ReadinessConfig}
import madrileno.main.AppConfig
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.RouteProvider
import madrileno.utils.lifecycle.LifecycleProvider
import madrileno.utils.mailer.MailerConfig
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

trait HealthCheckModule extends RouteProvider with LifecycleProvider {
  lazy val appConfig: AppConfig
  val transactor: Transactor
  val clock: Clock[IO]
  lazy val readinessConfig: ReadinessConfig
  protected def mailerConfig: MailerConfig
  given telemetryContext: TelemetryContext

  private val healthCheckRepository   = wire[HealthCheckRepository]
  private val postgresProbe           = wire[PostgresProbe]
  private val healthCheckService      = wire[HealthCheckService]
  private val healthCheckRouter       = wire[HealthCheckRouter]
  private val adminHealthCheckService = wire[AdminHealthCheckService]
  private val adminHealthCheckRouter  = wire[AdminHealthCheckRouter]

  abstract override def route: Route =
    super.route ~
      healthCheckRouter.routes

  def adminRoute: Route = adminHealthCheckRouter.routes

  override abstract def lifecycles: List[Resource[IO, Unit]] = {
    super.lifecycles :+ healthCheckService.readinessLifecycle
  }
}
