package madrileno.utils.mailer

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource

class MailSenderSpec extends AnyWordSpec with Matchers {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val applicationConf = ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.noSystem())

  private def load(overrides: String = ""): MailerConfig =
    ConfigSource.fromConfig(ConfigFactory.parseString(overrides).withFallback(applicationConf.getConfig("mailer"))).loadOrThrow[MailerConfig]

  "MailerConfig" should {
    "use smtp when MAILER_SENDER is not set, so a deployment that sets only the SMTP variables keeps working" in {
      load().sender shouldBe MailSenderKind.Smtp
    }

    "read sender = log" in {
      load("sender = log").sender shouldBe MailSenderKind.Log
    }

    "reject a sender it does not know" in {
      an[Exception] should be thrownBy load("sender = carrier-pigeon")
    }
  }

  "MailSender.fromConfig" should {
    "pick the SMTP sender for smtp" in {
      MailSender.fromConfig(load()) shouldBe a[SmtpSender]
    }

    "pick the logging sender for log" in {
      MailSender.fromConfig(load("sender = log")) shouldBe a[LoggingSender]
    }
  }

  "LoggingSender" should {
    val mail = SerializedMail(
      to = List("alice@example.com"),
      subject = "Your bid was outbid",
      body = SerializedMailBody.Both("Someone bid more than you.", "<p>Someone bid <b>more</b> than you.</p>"),
      cc = List("audit@example.com")
    )

    "describe a mail by its recipients, subject and text body, without the markup" in {
      val description = LoggingSender.describe(mail)
      description should include("alice@example.com")
      description should include("audit@example.com")
      description should include("Your bid was outbid")
      description should include("Someone bid more than you.")
      description should not include "<p>"
    }

    "describe an html-only mail by its size instead of dumping the markup" in {
      val description = LoggingSender.describe(mail.copy(body = SerializedMailBody.Html("<p>Only html</p>")))
      description should include("Your bid was outbid")
      description should include("html")
      description should not include "<p>"
    }

    "send completes without any server" in {
      new LoggingSender().send(mail).unsafeRunSync() shouldBe (())
    }
  }
}
