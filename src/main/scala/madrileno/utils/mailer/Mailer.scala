package madrileno.utils.mailer

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import io.circe.{Codec, Decoder, Encoder}
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.*

import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

class Mailer(
  sender: MailSender,
  schedulerClient: SchedulerClient,
  context: MailContext
)(using
  Clock[IO],
  TelemetryContext,
  UUIDGen[IO])
    extends LoggingSupport {

  val sendMailTask: OneTimeTask[SerializedMail] =
    Task.oneTime(TaskDescriptor[SerializedMail]("send-mail")) { task =>
      logger.info(s"Sending email to ${task.payload.to.mkString(", ")}: ${task.payload.subject}") *>
        sender.send(task.payload)
    }

  /** Schedule an email for delivery. If both `at` and `in` are provided, `at` takes precedence. */
  def send(
    to: List[String],
    template: EmailTemplate,
    lang: Language,
    from: Option[String] = None,
    cc: List[String] = Nil,
    bcc: List[String] = Nil,
    replyTo: Option[String] = None,
    attachments: List[Attachment] = Nil,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): IO[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.schedule(
          sendMailTask.instance(s"mail-$uuid", serialize(to, template, lang, from, cc, bcc, replyTo, attachments), at = resolveTime(now, at, in))
        )
      }
    }

  /** Schedule an email for delivery within an existing DB session. If both `at` and `in` are provided, `at` takes precedence. */
  def sendInSession(
    to: List[String],
    template: EmailTemplate,
    lang: Language,
    from: Option[String] = None,
    cc: List[String] = Nil,
    bcc: List[String] = Nil,
    replyTo: Option[String] = None,
    attachments: List[Attachment] = Nil,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): DB[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.scheduleInSession(
          sendMailTask.instance(s"mail-$uuid", serialize(to, template, lang, from, cc, bcc, replyTo, attachments), at = resolveTime(now, at, in))
        )
      }
    }

  /** Schedule an email for delivery within an existing DB transaction. If both `at` and `in` are provided, `at` takes precedence. */
  def sendTransactionally(
    to: List[String],
    template: EmailTemplate,
    lang: Language,
    from: Option[String] = None,
    cc: List[String] = Nil,
    bcc: List[String] = Nil,
    replyTo: Option[String] = None,
    attachments: List[Attachment] = Nil,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): DBInTransaction[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.scheduleTransactionally(
          sendMailTask.instance(s"mail-$uuid", serialize(to, template, lang, from, cc, bcc, replyTo, attachments), at = resolveTime(now, at, in))
        )
      }
    }

  private def resolveTime(
    now: Instant,
    at: Option[Instant],
    in: Option[FiniteDuration]
  ): Option[Instant] =
    at.orElse(in.map(d => now.plusMillis(d.toMillis)))

  private def serialize(
    to: List[String],
    template: EmailTemplate,
    lang: Language,
    from: Option[String],
    cc: List[String],
    bcc: List[String],
    replyTo: Option[String],
    attachments: List[Attachment]
  ): SerializedMail = {
    val rendered = template.render(context, lang)
    SerializedMail(
      to = to,
      subject = rendered.subject,
      body = rendered.body.serialize,
      from = from,
      cc = cc,
      bcc = bcc,
      replyTo = replyTo,
      attachments = attachments,
      inlineAttachments = rendered.inlineAttachments
    )
  }

  private given Codec[Array[Byte]] =
    Codec.from(Decoder.decodeString.map(Base64.getDecoder.decode), Encoder.encodeString.contramap(Base64.getEncoder.encodeToString))
  private given Codec[InlineAttachment]   = Codec.AsObject.derived
  private given Codec[Attachment]         = Codec.AsObject.derived
  private given Codec[SerializedMailBody] = Codec.AsObject.derived
  private given Codec[SerializedMail]     = Codec.AsObject.derived
}
