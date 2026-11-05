package madrileno.utils.observability

import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent}
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.OutputStreamAppender
import io.circe.parser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets
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
    event.setMessage("Payment \"declined\"")
    event.setThreadName("probe")
    event.setTimeStamp(System.currentTimeMillis())
    event.setMDCPropertyMap(Map("trace_id" -> "4bf92f3577b34da6a3ce929d0e0e4736", "span_id" -> "00f067aa0ba902b7").asJava)
    val encoder = console.getOrElse(fail("no console appender on the root logger")).getEncoder
    new String(encoder.encode(event), StandardCharsets.UTF_8)
  }

  "logback.xml" should {
    "write readable text lines to the console by default" in {
      val line = consoleLine(None)
      line should include("WARN")
      line should include("trace_id=4bf92f3577b34da6a3ce929d0e0e4736")
      parser.parse(line).isLeft shouldBe true
    }

    "write one ECS JSON object per line to the console when LOG_FORMAT is json" in {
      val line = consoleLine(Some("json"))
      line.trim.linesIterator.size shouldBe 1
      val json                                = parser.parse(line).getOrElse(fail(s"not JSON: $line"))
      def field(name: String): Option[String] = json.hcursor.downField(name).as[String].toOption
      field("@timestamp").isDefined shouldBe true
      field("log.level") shouldBe Some("WARN")
      field("message") shouldBe Some("Payment \"declined\"")
      field("log.logger") shouldBe Some("madrileno.Probe")
      field("ecs.version").isDefined shouldBe true
      field("trace_id") shouldBe Some("4bf92f3577b34da6a3ce929d0e0e4736")
      field("span_id") shouldBe Some("00f067aa0ba902b7")
    }
  }

  "LogFormat" should {
    "read the two values logback.xml has a console appender for" in {
      ConfigSource.string("format = text").at("format").loadOrThrow[LogFormat] shouldBe LogFormat.Text
      ConfigSource.string("format = json").at("format").loadOrThrow[LogFormat] shouldBe LogFormat.Json
    }

    "reject any other value, which would leave the console without an appender" in {
      ConfigSource.string("format = JSON").at("format").load[LogFormat].isLeft shouldBe true
      ConfigSource.string("format = ecs").at("format").load[LogFormat].isLeft shouldBe true
    }
  }
}
