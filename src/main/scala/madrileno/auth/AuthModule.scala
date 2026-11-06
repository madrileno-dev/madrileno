package madrileno.auth

import cats.effect.IO
import cats.effect.std.SecureRandom
import com.softwaremill.macwire.*
import madrileno.auth.domain.{AuthContext, Provider}
import madrileno.auth.emails.WelcomeEmailTemplate
import madrileno.auth.repositories.*
import madrileno.auth.routers.{AuthRouter, UserAuthenticator}
import madrileno.auth.services.*
import madrileno.user.domain.UserAccountDeleted
import madrileno.user.repositories.UserRepository
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.outbox.{OutboxSubscription, OutboxSubscriptionProvider}
import madrileno.utils.http.{AuthRouteProvider, RateLimiterRuntime, RouteProvider}
import madrileno.utils.mailer.{MailPreview, MailPreviewProvider, Mailer}
import madrileno.utils.observability.{Fingerprinter, TelemetryContext}
import madrileno.utils.task.{RecurringTaskProvider, Task}
import pl.iterators.stir.server.Route
import pureconfig.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

trait AuthModule extends RouteProvider with AuthRouteProvider with RecurringTaskProvider with MailPreviewProvider with OutboxSubscriptionProvider {
  val config: ConfigSource
  val jwtConfig: JwtService.Config                              = config.at("jwt").loadOrThrow[JwtService.Config]
  val authenticationServiceConfig: AuthenticationService.Config =
    config.at("refresh-token").loadOrThrow[AuthenticationService.Config]
  private val jwtService                   = wire[JwtService]
  private val fingerprinter: Fingerprinter = new Fingerprinter(config.at("logging").loadOrThrow[Fingerprinter.Config])
  given telemetryContext: TelemetryContext
  given secureRandom: SecureRandom[IO]
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  val rateLimiterRuntime: RateLimiterRuntime
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  lazy val userRepository: UserRepository
  lazy val mailer: Mailer

  val userAuthenticator: UserAuthenticator = wire[UserAuthenticator]

  protected lazy val firebaseConfig: FirebaseConfig = config.at("firebase").loadOrThrow[FirebaseConfig]
  protected lazy val devAuthConfig: DevAuthConfig   = config.at("dev-auth").loadOrThrow[DevAuthConfig]
  protected lazy val oidcConfig: OidcConfig         = config.at("oidc").loadOrThrow[OidcConfig]

  protected lazy val externalAuthVerifiers: AuthVerifiers = {
    val firebase: Option[(Provider, ExternalAuthVerifier)] =
      firebaseConfig.projectId
        .filter(_.nonEmpty)
        .map(projectId => Provider.Firebase -> new FirebaseService(projectId, new FirebaseKeyProvider(httpClient).keyFor))
    val dev: Option[(Provider, ExternalAuthVerifier)] =
      Option.when(devAuthConfig.enabled)(Provider.Dev -> DevAuthVerifier)
    val oidcEntries: Map[String, OidcProviderConfig] =
      oidcConfig.providers ++ oidcConfig.primary.toEntry
    val reservedConflicts = oidcEntries.keySet.intersect(AuthModule.ReservedProviderNames)
    require(reservedConflicts.isEmpty, s"oidc provider name(s) ${reservedConflicts.mkString(", ")} are reserved for built-in auth providers")
    val oidc: Iterable[(Provider, ExternalAuthVerifier)] =
      oidcEntries.map { case (name, providerConfig) =>
        val provider = Provider(name)
        provider -> OidcAuthVerifier(provider, providerConfig, httpClient, cacheRuntime)
      }
    AuthVerifiers((List(firebase, dev).flatten ++ oidc).toMap)
  }

  private val userAuthRepository        = wire[UserAuthRepository]
  private val refreshTokenRepository    = wire[RefreshTokenRepository]
  private val authAccountCleanupService = wire[AuthAccountCleanupService]
  private val authenticationService     = wire[AuthenticationService]
  private val authRouter                = wire[AuthRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ authRouter.authedRoutes(auth)
  }

  override abstract def route: Route = {
    super.route ~ authRouter.routes
  }

  override abstract def recurringTasks: List[Task[?]] = {
    super.recurringTasks :+ authenticationService.cleanupExpiredRefreshTokensTask
  }

  override abstract def mailPreviews: List[MailPreview] = {
    super.mailPreviews :+ WelcomeEmailTemplate.preview
  }

  override abstract def outboxSubscriptions: List[OutboxSubscription] = {
    super.outboxSubscriptions :+ OutboxSubscription[UserAccountDeleted]("auth")(authAccountCleanupService.onAccountDeleted)
  }
}

object AuthModule {
  private val ReservedProviderNames: Set[String] = Set("Firebase", "Dev")
}

final case class FirebaseConfig(projectId: Option[String]) derives ConfigReader

final case class DevAuthConfig(enabled: Boolean) derives ConfigReader

final case class OidcProviderConfig(
  issuer: String,
  audience: String,
  jwksUri: Option[String])
    derives ConfigReader

final case class OidcPrimaryConfig(
  name: String,
  issuer: Option[String],
  audience: Option[String],
  jwksUri: Option[String])
    derives ConfigReader {
  def toEntry: Option[(String, OidcProviderConfig)] =
    for {
      iss <- issuer.filter(_.nonEmpty)
      aud <- audience.filter(_.nonEmpty)
    } yield name -> OidcProviderConfig(iss, aud, jwksUri.filter(_.nonEmpty))
}

final case class OidcConfig(providers: Map[String, OidcProviderConfig], primary: OidcPrimaryConfig) derives ConfigReader
