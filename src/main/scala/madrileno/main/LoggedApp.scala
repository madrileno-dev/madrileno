package madrileno.main

import cats.effect.metrics.CpuStarvationWarningMetrics
import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import madrileno.utils.observability.{LogFormat, LoggingSupport}
import org.slf4j.bridge.SLF4JBridgeHandler
import pureconfig.ConfigSource

trait LoggedApp extends IOApp with LoggingSupport {
  protected def runLogged(args: List[String]): IO[ExitCode]

  final override def run(args: List[String]): IO[ExitCode] =
    IO.delay(ConfigSource.default.at("logging.format").load[LogFormat]).flatMap {
      case Left(failures) =>
        Console[IO].errorln(s"LOG_FORMAT must be text or json: ${failures.prettyPrint()}").as(ExitCode.Error)
      case Right(_) =>
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
