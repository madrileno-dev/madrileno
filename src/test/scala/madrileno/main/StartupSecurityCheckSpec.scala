package madrileno.main

import cats.effect.unsafe.implicits.global
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import madrileno.main.StartupSecurityCheck.Credentials
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

class StartupSecurityCheckSpec extends AnyWordSpec with Matchers {

  // The credentials shipped in .env.sample.
  private val sampleDefaults = Credentials(
    jwtSecret = "secret",
    fingerprintSecret = "fingerprint-secret",
    adminPassword = "admin",
    s3AccessKeyId = "minioadmin",
    s3SecretAccessKey = "minioadmin",
    objectStorageInUse = true,
    devAuthEnabled = true
  )

  // The fallback defaults compiled into application.conf (a different default family).
  private val confDefaults = Credentials(
    jwtSecret = "YouNeedToOverrideIt",
    fingerprintSecret = "YouNeedToOverrideIt",
    adminPassword = "admin",
    s3AccessKeyId = "minioadmin",
    s3SecretAccessKey = "minioadmin",
    objectStorageInUse = true,
    devAuthEnabled = false
  )

  private val overridden = Credentials(
    jwtSecret = "Zq7-not-a-default-jwt-secret-value",
    fingerprintSecret = "Km2-not-a-default-fingerprint-value",
    adminPassword = "Xy9-not-a-default-admin-password",
    s3AccessKeyId = "AKIANOTADEFAULTKEYID",
    s3SecretAccessKey = "not-a-default-s3-secret-access-key",
    objectStorageInUse = true,
    devAuthEnabled = false
  )

  private val allSecretPaths = Set(
    "jwt.secret",
    "logging.fingerprint-secret",
    "admin.password",
    "storage.object-storage.access-key-id",
    "storage.object-storage.secret-access-key"
  )

  "StartupSecurityCheck.check" should {
    "allow shipped defaults in Dev" in {
      StartupSecurityCheck.check(Environment.Dev, sampleDefaults) shouldBe empty
    }

    "flag every shipped default plus dev auth in Prod" in {
      StartupSecurityCheck.check(Environment.Prod, sampleDefaults).map(_.path).toSet shouldBe
        allSecretPaths + "dev-auth.enabled"
    }

    "flag the application.conf fallback secrets too" in {
      val paths = StartupSecurityCheck.check(Environment.Prod, confDefaults).map(_.path).toSet
      paths should contain("jwt.secret")
      paths should contain("logging.fingerprint-secret")
      paths should not contain "dev-auth.enabled" // disabled here
    }

    "pass when every secret is overridden and dev auth is off" in {
      StartupSecurityCheck.check(Environment.Prod, overridden) shouldBe empty
    }

    "flag dev auth on its own when it is the only unsafe setting" in {
      StartupSecurityCheck.check(Environment.Prod, overridden.copy(devAuthEnabled = true)).map(_.path) shouldBe
        List("dev-auth.enabled")
    }

    "flag a secret left empty or blank" in {
      val blank = overridden.copy(jwtSecret = "", fingerprintSecret = "  ", adminPassword = "", s3AccessKeyId = "", s3SecretAccessKey = " ")
      StartupSecurityCheck.check(Environment.Prod, blank).map(v => v.path -> v.detail).toSet shouldBe allSecretPaths.map(_ -> "is empty")
    }

    "flag a JWT or fingerprint secret shorter than 32 characters and an admin password shorter than 16" in {
      val short = overridden.copy(jwtSecret = "a" * 31, fingerprintSecret = "b" * 31, adminPassword = "c" * 15)
      StartupSecurityCheck.check(Environment.Prod, short).map(v => v.path -> v.detail) shouldBe List(
        "jwt.secret"                 -> "is shorter than 32 characters",
        "logging.fingerprint-secret" -> "is shorter than 32 characters",
        "admin.password"             -> "is shorter than 16 characters"
      )
    }

    "accept secrets of exactly the minimum length" in {
      StartupSecurityCheck.check(
        Environment.Prod,
        overridden.copy(jwtSecret = "a" * 32, fingerprintSecret = "b" * 32, adminPassword = "c" * 16)
      ) shouldBe empty
    }

    "leave the S3 keys alone when objects are stored on disk" in {
      val onDisk = overridden.copy(s3AccessKeyId = "", s3SecretAccessKey = "minioadmin", objectStorageInUse = false)
      StartupSecurityCheck.check(Environment.Prod, onDisk) shouldBe empty
    }

    "enforce in Test, not only Prod and Staging (only Dev is exempt)" in {
      StartupSecurityCheck.check(Environment.Test, sampleDefaults) should not be empty
      StartupSecurityCheck.check(Environment.Staging, sampleDefaults) should not be empty
    }
  }

  "StartupSecurityCheck.render" should {
    "name the environment and the env var to set for each violation" in {
      val message = StartupSecurityCheck.render(Environment.Prod, StartupSecurityCheck.check(Environment.Prod, sampleDefaults))
      message should include("Prod")
      message should include("JWT_SECRET")
      message should include("DEV_AUTH_ENABLED")
      message should include("S3_SECRET_ACCESS_KEY")
    }
  }

  "StartupSecurityCheck.enforce" should {
    def enforce(env: Map[String, String]): Either[Throwable, Unit] = {
      val config =
        ConfigFactory.parseMap(env.asJava).withFallback(ConfigFactory.parseResources("application.conf")).resolve(ConfigResolveOptions.noSystem())
      StartupSecurityCheck.enforce(Environment.Prod, ConfigSource.fromConfig(config)).attempt.unsafeRunSync()
    }
    val strong = Map("JWT_SECRET" -> "j" * 32, "FINGERPRINT_SECRET" -> "f" * 32, "ADMIN_PASSWORD" -> "p" * 16, "OBJECT_STORAGE" -> "disk")

    "start with strong secrets from the environment and disk storage, whatever the S3 keys say" in {
      enforce(strong) shouldBe Right(())
    }

    "refuse an empty JWT_SECRET from the environment" in {
      enforce(strong + ("JWT_SECRET" -> "")).left.map(_.getMessage) should matchPattern {
        case Left(message: String) if message.contains("jwt.secret is empty; set JWT_SECRET") && !message.contains("S3_") =>
      }
    }
  }

  "StartupSecurityCheck.requireDev" should {
    "pass in Dev and raise otherwise" in {
      noException should be thrownBy StartupSecurityCheck.requireDev(Environment.Dev, "SomeTool")
      val raised = the[IllegalArgumentException] thrownBy StartupSecurityCheck.requireDev(Environment.Prod, "SomeTool")
      raised.getMessage should include("SomeTool")
      raised.getMessage should include("Prod")
    }
  }
}
