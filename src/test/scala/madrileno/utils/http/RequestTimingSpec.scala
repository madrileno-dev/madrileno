package madrileno.utils.http

import cats.data.Kleisli
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger as LogbackLogger, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.db.transactor.DatabaseTime
import madrileno.utils.observability.TelemetryContext
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Response, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.{Logger, LoggerFactory}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class RequestTimingSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private def root: LogbackLogger = LoggerFactory.getILoggerFactory match {
    case context: LoggerContext => context.getLogger(Logger.ROOT_LOGGER_NAME)
    case other                  => fail(s"expected logback, found ${other.getClass.getName}")
  }

  private val capturedLogs: Resource[IO, ListAppender[ILoggingEvent]] =
    Resource.make(IO.delay {
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      root.addAppender(appender)
      appender
    })(appender => IO.delay(root.detachAppender(appender)) *> IO.delay(appender.stop()))

  private def timingLines(appender: ListAppender[ILoggingEvent]): List[ILoggingEvent] =
    appender.list.asScala.toList.filter(_.getLoggerName == RequestTiming.getClass.getName)

  private def serve(app: DatabaseTime => HttpApp[IO], request: Request[IO]): IO[(Either[Throwable, Response[IO]], List[ILoggingEvent])] =
    DatabaseTime.create.flatMap { time =>
      capturedLogs.use(appender => RequestTiming(app(time), time).run(request).attempt.map(_ -> timingLines(appender)))
    }

  "RequestTiming" should {
    "log one line per request with the status, the duration and the database share" in {
      val app = (time: DatabaseTime) => Kleisli((_: Request[IO]) => time.measure(IO.sleep(50.millis)).as(Response[IO](Status.Created)))
      serve(app, Request[IO](Method.GET, uri"/v1/auctions?token=secret")).map { case (response, lines) =>
        response.map(_.status) shouldBe Right(Status.Created)
        lines.map(_.getLevel) shouldBe List(Level.INFO)
        val line = lines.head
        line.getFormattedMessage should fullyMatch regex """GET /v1/auctions 201 in \d+ ms, \d+ ms of it in 1 database call"""
        line.getFormattedMessage should not include "secret"
        val fields = line.getMDCPropertyMap.asScala
        fields("http.request.method") shouldBe "GET"
        fields("url.path") shouldBe "/v1/auctions"
        fields("http.response.status_code") shouldBe "201"
        fields("db.calls") shouldBe "1"
        fields("db.duration").toLong should be >= 50.millis.toNanos
        fields("event.duration").toLong should be >= fields("db.duration").toLong
      }
    }

    "log a request that failed before it had a response, and pass the failure on" in {
      val app = (_: DatabaseTime) => Kleisli((_: Request[IO]) => IO.raiseError[Response[IO]](new RuntimeException("boom")))
      serve(app, Request[IO](Method.POST, uri"/v1/bids")).map { case (response, lines) =>
        response.isLeft shouldBe true
        lines.map(_.getFormattedMessage) should matchPattern { case List(message: String) if message.startsWith("POST /v1/bids failed in ") => }
      }
    }

    "log health checks at debug" in {
      val app = (_: DatabaseTime) => Kleisli((_: Request[IO]) => IO.pure(Response[IO](Status.Ok)))
      serve(app, Request[IO](Method.GET, uri"/v1/health-check")).map { case (_, lines) =>
        lines.map(_.getLevel) shouldBe List(Level.DEBUG)
        lines.head.getFormattedMessage should endWith("no database calls")
      }
    }
  }
}
