package madrileno.main

import cats.effect.metrics.CpuStarvationWarningMetrics
import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import madrileno.utils.observability.LoggingSupport
import org.slf4j.bridge.SLF4JBridgeHandler

trait LoggedApp extends IOApp with LoggingSupport {
  protected def runLogged(args: List[String]): IO[ExitCode]

  protected def logFormat: IO[Option[String]] = IO.delay(Option(System.getProperty("LOG_FORMAT")).orElse(sys.env.get("LOG_FORMAT")))

  final override def run(args: List[String]): IO[ExitCode] =
    logFormat.flatMap {
      case Some(format) if !Set("text", "json").contains(format) =>
        Console[IO].errorln(s"LOG_FORMAT must be text or json, got '$format'").as(ExitCode.Error)
      case _ =>
        IO.delay {
          SLF4JBridgeHandler.removeHandlersForRootLogger()
          SLF4JBridgeHandler.install()
        } *> runLogged(args).handleErrorWith(e => loggerWithoutTracing.error(e)("Application stopped with an error").as(ExitCode.Error))
    }

  override protected def reportFailure(err: Throwable): IO[Unit] =
    loggerWithoutTracing.error(err)("Unhandled error in a background fiber")

  override protected def onCpuStarvationWarn(metrics: CpuStarvationWarningMetrics): IO[Unit] =
    loggerWithoutTracing.warn(
      s"CPU starvation: timers fired ${metrics.clockDrift} late. Look for blocking calls outside IO.blocking, or give the app more CPU"
    )
}
