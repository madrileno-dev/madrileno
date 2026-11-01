package madrileno.utils.mailer

import cats.effect.IO
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import pureconfig.generic.derivation.EnumConfigReader

trait MailSender {
  def send(mail: SerializedMail): IO[Unit]
}

object MailSender {
  def fromConfig(config: MailerConfig)(using TelemetryContext): MailSender = config.sender match {
    case MailSenderKind.Smtp => new SmtpSender(config)
    case MailSenderKind.Log  => new LoggingSender()
  }
}

enum MailSenderKind derives EnumConfigReader {
  case Smtp, Log
}

class LoggingSender(using TelemetryContext) extends MailSender with LoggingSupport {
  override def send(mail: SerializedMail): IO[Unit] = logger.info(LoggingSender.describe(mail))
}

object LoggingSender {
  def describe(mail: SerializedMail): String = {
    val recipients  = (mail.to.map("to " + _) ++ mail.cc.map("cc " + _) ++ mail.bcc.map("bcc " + _)).mkString(", ")
    val attachments = if (mail.attachments.isEmpty) "" else s"; ${mail.attachments.size} attachment(s)"
    val body        = mail.body match {
      case SerializedMailBody.Text(text)    => text
      case SerializedMailBody.Both(text, _) => text
      case SerializedMailBody.Html(html)    => s"<html body, ${html.length} chars>"
    }
    s"Mail not sent (mailer.sender = log): $recipients; subject: ${mail.subject}$attachments\n$body"
  }
}
