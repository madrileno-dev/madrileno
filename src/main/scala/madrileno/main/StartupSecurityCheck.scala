package madrileno.main

import cats.effect.IO
import madrileno.auth.DevAuthConfig
import madrileno.auth.services.JwtService
import madrileno.utils.observability.Fingerprinter
import madrileno.utils.storage.StorageConfig
import pureconfig.*

object StartupSecurityCheck {

  final case class Violation(
    path: String,
    envVar: String,
    detail: String)

  final case class Credentials(
    jwtSecret: String,
    fingerprintSecret: String,
    adminPassword: String,
    s3AccessKeyId: String,
    s3SecretAccessKey: String,
    devAuthEnabled: Boolean)

  private val shippedDefaults = Set("secret", "YouNeedToOverrideIt", "admin", "minioadmin", "fingerprint-secret")

  def check(environment: Environment, credentials: Credentials): List[Violation] =
    if (environment == Environment.Dev) Nil
    else {
      val defaulted = List(
        ("jwt.secret", "JWT_SECRET", credentials.jwtSecret),
        ("logging.fingerprint-secret", "FINGERPRINT_SECRET", credentials.fingerprintSecret),
        ("admin.password", "ADMIN_PASSWORD", credentials.adminPassword),
        ("storage.object-storage.access-key-id", "S3_ACCESS_KEY_ID", credentials.s3AccessKeyId),
        ("storage.object-storage.secret-access-key", "S3_SECRET_ACCESS_KEY", credentials.s3SecretAccessKey)
      ).collect {
        case (path, envVar, value) if shippedDefaults(value) => Violation(path, envVar, "is a shipped default")
      }
      val devAuth =
        Option.when(credentials.devAuthEnabled)(Violation("dev-auth.enabled", "DEV_AUTH_ENABLED", "is on, so dev auth mints a session for any email"))
      defaulted ++ devAuth
    }

  def render(environment: Environment, violations: List[Violation]): String = {
    val lines = violations.map(violation => s"  - ${violation.path} ${violation.detail}; set ${violation.envVar}")
    (s"Refusing to start with app.environment=$environment and insecure configuration:" :: lines).mkString("\n")
  }

  def requireDev(
    environment: Environment,
    subject: String,
    action: String = "boot"
  ): Unit =
    require(environment == Environment.Dev, s"$subject refuses to $action with app.environment=$environment. Set APP_ENVIRONMENT=dev.")

  def enforce(environment: Environment, config: ConfigObjectSource): IO[Unit] =
    IO.delay(credentials(config)).flatMap { creds =>
      val violations = check(environment, creds)
      IO.raiseWhen(violations.nonEmpty)(new IllegalStateException(render(environment, violations)))
    }

  private def credentials(config: ConfigObjectSource): Credentials = {
    val storage = config.at("storage").loadOrThrow[StorageConfig]
    Credentials(
      jwtSecret = config.at("jwt").loadOrThrow[JwtService.Config].secret,
      fingerprintSecret = config.at("logging").loadOrThrow[Fingerprinter.Config].fingerprintSecret,
      adminPassword = config.at("admin").loadOrThrow[AdminConfig].password,
      s3AccessKeyId = storage.objectStorage.accessKeyId,
      s3SecretAccessKey = storage.objectStorage.secretAccessKey,
      devAuthEnabled = config.at("dev-auth").loadOrThrow[DevAuthConfig].enabled
    )
  }
}
