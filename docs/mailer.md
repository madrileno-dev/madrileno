# Mailer

The mailer renders typed email templates, serializes them, and hands them to the scheduler as one-time tasks. SMTP delivery happens asynchronously from the request that triggered the email; if delivery fails, the scheduler retries.

In dev, an SMTP testing server (Mailpit) catches everything in a web UI so you can see what your users would see without sending real mail.

## The flow

```
service: mailer.send(to, template, lang)
                │
                ▼
   serialize(template.render(ctx, lang)) → SerializedMail
                │
                ▼
   schedulerClient.schedule(sendMailTask.instance(uuid, serialized, at))
                │
                │  (returned to caller as IO[Boolean])
                ▼
        scheduled_task row inserted
                ⋮
        scheduler picks it up (async)
                │
                ▼
   sendMailTask.execution(task) → sender.send(payload)
                │
                ▼
        SMTP delivery (or retry)
```

The send call returns once the row is in the queue. Delivery is the scheduler's problem. This means email sending is "at-least-once" with retries, not "synchronous and confirmed" — design accordingly.

## Pieces

| File                                  | What it does                                                                                |
| ------------------------------------- | ------------------------------------------------------------------------------------------- |
| `Mailer`                              | Public API. `send` / `sendInSession` / `sendTransactionally`. Owns `sendMailTask`.          |
| `EmailTemplate`                       | Trait with one method: `render(ctx, lang): RenderedMail`. Templates live in feature modules. |
| `RenderedMail` / `MailBody`           | The intermediate output: subject, text/html body, optional inline attachments.              |
| `SerializedMail` / `SerializedMailBody` | Wire format the scheduler row stores. HTML tags get rendered to a string at serialization. |
| `MailSender`                          | What `Mailer` delivers through: `SmtpSender` (Jakarta Mail, the configured SMTP host) or `LoggingSender` (the application log; `mailer.sender = log`). |
| `MailerConfig`                        | host / port / credentials / from address / TLS — pureconfig-loaded from `mailer.*`.         |
| `MailPreview` / `MailPreviewRouter`   | Dev-mode `/admin/mail-previews` UI for rendering templates without sending.                  |

## Templates

Templates are plain classes that take their data in the constructor and produce a `RenderedMail`. The bodies are scalatags — Scala-native HTML DSL, type-checked, no string concatenation:

```scala
class OutbidEmailTemplate(
  wineName: WineName,
  newBidAmount: Price,
  currency: Currency
) extends EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail = lang match {
    case Language.En =>
      RenderedMail(
        subject = s"You've been outbid on $wineName",
        body = MailBody.Html(
          html(
            head(tag("style")(raw(""" body { … } """))),
            body(
              div(cls := "container")(
                p("New high bid on ", span(cls := "highlight")(wineName.toString), "."),
                a(cls := "button", href := ctx.baseUrl.toString)("Place a New Bid")
              )
            )
          )
        )
      )
  }
}

object OutbidEmailTemplate {
  val preview: MailPreview =
    MailPreview("outbid-notification",
      OutbidEmailTemplate(WineName("Château Margaux 2015"), Price(BigDecimal(350)), Currency.getInstance("EUR")))
}
```

Three things to know:

- **Subject and body together.** `RenderedMail` is the unit of templating. Don't render subject and body separately — the subject is part of the template's responsibility.
- **`MailBody` has three shapes**: `Text`, `Html`, and `Both`. Most templates use `Html`. `Both` is for ensuring text-only mail clients still get readable content.
- **`ctx: MailContext` carries application-level data** every template needs: currently just `baseUrl` (for absolute links). Add fields here if you have data that's universally useful across templates.

The companion's `preview: MailPreview` is what makes the template show up in the dev preview UI. Always provide one — see "Previews" below.

## Look and feel

Templates don't carry their own CSS. `EmailLayout` (in `madrileno.utils.mailer`) owns the shell — canvas, card, wordmark header, heading, CTA button, footer — so every email looks the same and a restyle happens in one place:

```scala
EmailLayout.page(
  "You've been outbid!",
  p("Someone has placed a higher bid on ", EmailLayout.highlight(wineName.toString), "."),
  EmailLayout.cta(ctx.baseUrl.toString, "Place a New Bid")
)
```

Its palette mirrors the [reference frontend](frontend.md)'s design tokens (`src/styles/tailwind.css`), resolved to hex — email clients support neither `oklch` nor CSS variables. Rebrand by editing the constants at the top of `EmailLayout`; keep them in step with the frontend's tokens so mail and app stay recognisably the same product. The emails are deliberately light-only (`color-scheme: light`) rather than mirroring the frontend's dark mode, since client dark-mode handling is inconsistent.

## Sending

`Mailer` exposes three send variants, mirroring the `DB` / `DBInTransaction` distinction:

```scala
class Mailer(...) {
  def send(...): IO[Boolean]                            // own session, fire-and-forget
  def sendInSession(...): DB[Boolean]                   // join an existing session
  def sendTransactionally(...): DBInTransaction[Boolean] // join an existing tx
}
```

Use `sendTransactionally` when the email enqueue must be atomic with the database state change that triggered it. The auction `notifyOutbid` flow does this — the email gets queued in the same transaction as the bid row write, so a transaction rollback also rolls back the email:

```scala
transactor.inTransaction {
  for {
    _ <- bidRepository.save(newBid)
    _ <- mailer.sendTransactionally(
           to = List(previousHighest.bidder.email),
           template = OutbidEmailTemplate(auction.wineName, newBid.amount, auction.currency),
           lang = Language.En
         )
  } yield ()
}
```

Optional parameters:

- `at: Option[Instant]` — schedule for a specific time.
- `in: Option[FiniteDuration]` — schedule relative to now.
- `from: Option[String]` — overrides `mailer.from-address`.
- `cc: List[String]`, `bcc: List[String]`, `replyTo: Option[String]` — standard SMTP headers.
- `attachments: List[Attachment]` — files attached to the email.

If both `at` and `in` are provided, `at` wins.

## How it actually delivers

`Mailer` constructs one `OneTimeTask[SerializedMail]` named `"send-mail"` and registers it via `oneTimeTasks: List[OneTimeTask[?]]`. Every send call schedules a fresh instance:

```scala
val sendMailTask: OneTimeTask[SerializedMail] =
  Task.oneTime(TaskDescriptor[SerializedMail]("send-mail")) { task =>
    logger.info(s"Sending email to ${task.payload.to.mkString(", ")}: ${task.payload.subject}") *>
      sender.send(task.payload)
  }
```

Failures bubble up to the scheduler, which retries with exponential backoff. After `max-retries` (configurable, no cap by default) the row is dropped with an error log. See [scheduler.md](scheduler.md).

The serialized payload includes the rendered HTML and all attachments base64-encoded into the row. Don't put 50 MB attachments in emails — the row holds the whole thing in `jsonb` until delivery.

## Configuration

```hocon
mailer {
  host         = ${MAILER_HOST}                # required
  port         = 587
  username     = ${?MAILER_USERNAME}           # optional (omit for unauth dev SMTP)
  password     = ${?MAILER_PASSWORD}
  from-address = "noreply@example.com"         # default sender
  from-name    = "App"                         # display name
  tls          = true                          # STARTTLS
  sender       = "smtp"                        # or "log": write each mail to the application log instead of sending
}
```

`sender` picks the `MailSender`: `SmtpSender` for `smtp`, `LoggingSender` for `log`. The stock `.env` sets `MAILER_SENDER=log`, so the Postgres-only dev stack needs no SMTP server: each mail shows up in the log as one entry with recipients, subject and the text body (HTML-only mails are summarised by size). The scheduler path is the same either way, so retries, the `send-mail` task and the previews UI all behave as in production.

With the `full` compose profile (`docker compose --profile full up -d`), Mailpit runs on `localhost:51025` (SMTP) with no auth and TLS off; set `MAILER_SENDER=smtp` and the default `.env` matches it. The compose file publishes SMTP on `MAILER_PORT` from `.env`, so a changed port moves both sides.

For production, leave `sender` at its `smtp` default and point at SES / SendGrid / your provider's SMTP relay. The mailer doesn't ship API-based delivery (e.g., SES native API) — if you need that, add a `MailSender` implementation and a `MailSenderKind` for it.

## Previews

In dev (`app.environment = "dev"`), `/admin/mail-previews` lists every `MailPreview` from every module's `mailPreviews: List[MailPreview]`, with its rendered HTML and text shown side-by-side. Edit a template, refresh the page, see the result without sending.

To register a preview, add it to your module's `mailPreviews`:

```scala
override abstract def mailPreviews: List[MailPreview] =
  super.mailPreviews :+ OutbidEmailTemplate.preview :+ AuctionClosedEmailTemplate.sellerPreview
```

The preview value usually lives on the template's companion as a `val preview: MailPreview` constructed with realistic example data. Make the example data realistic — long names, currencies with awkward symbols, edge-case prices — so the preview catches layout bugs.

## Internationalisation

`Language` is a Scala 3 enum. Right now it's `case En`. Adding more languages means three things:

1. Adding a case (`case De`, `case Fr`).
2. Pattern-matching exhaustively on it in every `EmailTemplate.render`. Compiler tells you what you forgot.
3. Threading the user's preferred language from somewhere (User row, request header) into the `mailer.send(... , lang = …)` call.

The pattern-match-on-enum approach scales to a handful of languages. If you have dozens, you'd want a typesafe message-bundle library — out of scope for the template.

## Testing

`TestMailpit` (mixed into specs that need real SMTP) starts Mailpit in a testcontainer and exposes its host/port. `MailerConfig` for the test points there; the test sends a real email through the mailer + scheduler path, then queries Mailpit's HTTP API to read what landed.

For unit tests of templates themselves, just call `template.render(MailContext(testBaseUrl), Language.En)` and assert on the `RenderedMail`.

## Where to look next

- [scheduler.md](scheduler.md) — `sendMailTask` is a `OneTimeTask`; everything in that doc applies (retries, idempotency, the admin UI).
- [http.md](http.md) — the dev-only `/admin/mail-previews` is one of the routes ApplicationLoader gates behind `appConfig.environment == Environment.Dev`.
