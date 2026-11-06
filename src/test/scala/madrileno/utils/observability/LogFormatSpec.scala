package madrileno.utils.observability

import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent, ThrowableProxy}
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.OutputStreamAppender
import io.circe.parser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger

import java.nio.charset.StandardCharsets
import java.util.logging.{Level as JulLevel, Logger as JulLogger}
import scala.jdk.CollectionConverters.*

class LogFormatSpec extends AnyWordSpec with Matchers {

  private def configured(logFormat: Option[String]): LoggerContext = {
    val context = new LoggerContext
    logFormat.foreach(context.putProperty("LOG_FORMAT", _))
    val configurator = new JoranConfigurator
    configurator.setContext(context)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    context
  }

  private def consoleLine(logFormat: Option[String]): String = {
    val context = configured(logFormat)
    val root    = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val console = root.iteratorForAppenders().asScala.collectFirst {
      case appender: OutputStreamAppender[ILoggingEvent @unchecked] if appender.getName.startsWith("Console") => appender
    }
    val event = new LoggingEvent()
    event.setLoggerContext(context)
    event.setLoggerName("madrileno.Probe")
    event.setLevel(Level.WARN)
    event.setMessage("Payment declined")
    event.setThreadName("probe")
    event.setTimeStamp(System.currentTimeMillis())
    event.setMDCPropertyMap(Map("trace_id" -> "4bf92f3577b34da6a3ce929d0e0e4736").asJava)
    event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("first line\nsecond line")))
    val encoder = console.getOrElse(fail("no console appender on the root logger")).getEncoder
    new String(encoder.encode(event), StandardCharsets.UTF_8)
  }

  "logback.xml" should {
    "write readable text lines to the console by default" in {
      assume(!sys.env.contains("LOG_FORMAT"), "logback reads LOG_FORMAT from the environment")
      val line = consoleLine(None)
      line should include("WARN")
      line should include("trace_id=4bf92f3577b34da6a3ce929d0e0e4736")
      parser.parse(line).isLeft shouldBe true
    }

    "leave java.util.logging at INFO, so the OTLP sender's debug output isn't exported back through the OpenTelemetry appender" in {
      val julRoot = JulLogger.getLogger("")
      julRoot.setLevel(JulLevel.INFO)
      configured(Some("json")).isStarted shouldBe true
      julRoot.getLevel shouldBe JulLevel.INFO
    }

    "write one JSON object per line, carrying the trace id and the stack trace, when LOG_FORMAT is json" in {
      val line = consoleLine(Some("json"))
      line.trim.linesIterator.size shouldBe 1
      val json                                = parser.parse(line).getOrElse(fail(s"not JSON: $line"))
      def field(name: String): Option[String] = json.hcursor.downField(name).as[String].toOption
      field("trace_id") shouldBe Some("4bf92f3577b34da6a3ce929d0e0e4736")
      field("error.stack_trace").getOrElse(fail("no stack trace")) should include("first line\nsecond line")
    }
  }
}
