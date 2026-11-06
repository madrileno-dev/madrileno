package madrileno.utils.mailer

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class MailSerializationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val capturedMail = new AtomicReference[SerializedMail]()

  private val capturingSender = new SmtpSender(MailerConfig(host = "localhost", fromAddress = "test@test.com")) {
    override def send(mail: SerializedMail): IO[Unit] = IO(capturedMail.set(mail))
  }

  private lazy val scheduler = Scheduler(transactor, SchedulerConfig(pollingInterval = 1.second))
  private lazy val client    = scheduler.client
  private lazy val context   = MailContext(baseUrl = URI("https://example.com"))
  private lazy val mailer    = new Mailer(capturingSender, client, context)

  "Mail serialization round-trip through scheduler" should {
    "preserve HTML email content" in {
      import scalatags.Text.all.{a as stA, *}

      val template: EmailTemplate = (ctx, _) =>
        RenderedMail(
          subject = "Test subject",
          body = MailBody.Html(html(body(h1("Hello"), p("World ", strong("bold")), stA(href := ctx.baseUrl.toString)("Link"))))
        )

      def waitForCapture(remaining: FiniteDuration = 5.seconds): IO[SerializedMail] =
        IO(Option(capturedMail.get())).flatMap {
          case Some(mail)                         => IO.pure(mail)
          case None if remaining <= Duration.Zero =>
            IO.raiseError(new RuntimeException("Timed out waiting for mail capture"))
          case None => IO.sleep(100.millis) *> waitForCapture(remaining - 100.millis)
        }

      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _    <- mailer.send(to = List("user@example.com"), template = template, lang = Language.En)
            mail <- waitForCapture()
          } yield mail
        }
        .map { mail =>
          mail.to shouldBe List("user@example.com")
          mail.subject shouldBe "Test subject"
          mail.body match {
            case SerializedMailBody.Html(h) =>
              h should include("<h1>")
              h should include("Hello")
              h should include("<strong>bold</strong>")
              h should include("https://example.com")
            case other => fail(s"Expected Html, got $other")
          }
        }
    }
  }
}
