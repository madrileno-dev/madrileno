package madrileno.main

import cats.effect.metrics.CpuStarvationWarningMetrics
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{ExitCode, IO}
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger as JulLogger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class LoggedAppSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private object FailingApp extends LoggedApp {
    override protected def runLogged(args: List[String]): IO[ExitCode] = IO.raiseError(new IllegalStateException("port 8080 in use"))
  }

  private object QuietApp extends LoggedApp {
    override protected def runLogged(args: List[String]): IO[ExitCode] =
      IO.delay(JulLogger.getLogger("io.opentelemetry.exporter.internal.http.HttpExporter").severe("Failed to export spans")).as(ExitCode.Success)

    def starving: IO[Unit] = onCpuStarvationWarn(CpuStarvationWarningMetrics(1.second, 200.millis, 0.1, 1.second))
  }

  private def capturingLogs[A](io: IO[A]): IO[(A, List[ILoggingEvent])] = {
    val attach = IO {
      LoggerFactory.getILoggerFactory match {
        case context: LoggerContext =>
          val root     = context.getLogger(Logger.ROOT_LOGGER_NAME)
          val appender = new ListAppender[ILoggingEvent]
          appender.start()
          root.addAppender(appender)
          (root, appender)
        case other => fail(s"expected logback, found ${other.getClass.getName}")
      }
    }
    attach.bracket { case (_, appender) => io.map(result => (result, appender.list.asScala.toList)) } { case (root, appender) =>
      IO { root.detachAppender(appender); appender.stop() }
    }
  }

  "LoggedApp" should {
    "log a fatal error and exit with an error code instead of letting IOApp print it raw" in {
      capturingLogs(FailingApp.run(Nil)).asserting { case (code, events) =>
        code shouldBe ExitCode.Error
        val stopped = events.find(_.getFormattedMessage == "Application stopped with an error").getOrElse(fail(s"not logged: $events"))
        stopped.getThrowableProxy.getMessage shouldBe "port 8080 in use"
      }
    }

    "refuse an unknown LOG_FORMAT before running, since logback would leave the console without an appender" in {
      val ran = new AtomicBoolean(false)
      object App extends LoggedApp {
        override protected def runLogged(args: List[String]): IO[ExitCode] = IO.delay(ran.set(true)).as(ExitCode.Success)
      }
      IO.delay(System.setProperty("LOG_FORMAT", "JSON"))
        .bracket(_ => App.run(Nil))(_ => IO.delay(System.clearProperty("LOG_FORMAT")).void)
        .asserting { code =>
          code shouldBe ExitCode.Error
          ran.get shouldBe false
        }
    }

    "run when LOG_FORMAT is one logback.xml has a console appender for" in {
      IO.delay(System.setProperty("LOG_FORMAT", "json"))
        .bracket(_ => QuietApp.run(Nil))(_ => IO.delay(System.clearProperty("LOG_FORMAT")).void)
        .asserting(_ shouldBe ExitCode.Success)
    }

    "send java.util.logging records, such as the OTLP exporter's, through logback" in {
      capturingLogs(QuietApp.run(Nil)).asserting { case (code, events) =>
        code shouldBe ExitCode.Success
        events.map(e => (e.getLoggerName, e.getLevel.toString, e.getFormattedMessage)) should contain(
          ("io.opentelemetry.exporter.internal.http.HttpExporter", "ERROR", "Failed to export spans")
        )
      }
    }

    "log CPU starvation warnings instead of printing them to stderr" in {
      capturingLogs(QuietApp.starving).asserting { case (_, events) =>
        events.exists(e =>
          e.getLevel.toString == "WARN" && e.getFormattedMessage.startsWith("CPU starvation: timers fired 200 milliseconds late")
        ) shouldBe true
      }
    }
  }
}
