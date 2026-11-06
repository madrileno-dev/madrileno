package madrileno.main

import madrileno.main.StartupSecurityCheck.Credentials
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StartupSecurityCheckSpec extends AnyWordSpec with Matchers {

  // The credentials shipped in .env.sample.
  private val sampleDefaults = Credentials(
    jwtSecret = "secret",
    fingerprintSecret = "fingerprint-secret",
    adminPassword = "admin",
    s3AccessKeyId = "minioadmin",
    s3SecretAccessKey = "minioadmin",
    devAuthEnabled = true
  )

  // The fallback defaults compiled into application.conf (a different default family).
  private val confDefaults = Credentials(
    jwtSecret = "YouNeedToOverrideIt",
    fingerprintSecret = "YouNeedToOverrideIt",
    adminPassword = "admin",
    s3AccessKeyId = "minioadmin",
    s3SecretAccessKey = "minioadmin",
    devAuthEnabled = false
  )

  private val overridden = Credentials(
    jwtSecret = "Zq7-not-a-default-jwt-secret-value",
    fingerprintSecret = "Km2-not-a-default-fingerprint-value",
    adminPassword = "Xy9-not-a-default-admin-password",
    s3AccessKeyId = "AKIANOTADEFAULTKEYID",
    s3SecretAccessKey = "not-a-default-s3-secret-access-key",
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

  "StartupSecurityCheck.requireDev" should {
    "pass in Dev and raise otherwise" in {
      noException should be thrownBy StartupSecurityCheck.requireDev(Environment.Dev, "SomeTool")
      val raised = the[IllegalArgumentException] thrownBy StartupSecurityCheck.requireDev(Environment.Prod, "SomeTool")
      raised.getMessage should include("SomeTool")
      raised.getMessage should include("Prod")
    }
  }
}
