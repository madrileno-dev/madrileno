#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8
//> using file app
//> using mainClass AppMain

import app.*
import mainargs.{arg, main, Flag, ParserForMethods}

import scala.concurrent.duration.*

object AppMain {
  private val root = os.pwd

  @main(doc = "Compile, apply pending migrations, start the app in the background and wait until it answers the health check.")
  def start(
    @arg(doc = "seconds to wait for the health check (default 60)") timeout: Int = 60,
    @arg(name = "no-compile", doc = "skip `sbt --client compile`; run whatever is compiled") noCompile: Flag,
    @arg(name = "no-migrate", doc = "skip `migrate` before starting") noMigrate: Flag
  ): Unit =
    finish(Launcher.start(system(requireEnv = true, timeout = timeout), compileFirst = !noCompile.value, migrateFirst = !noMigrate.value))

  @main(doc = "Stop the app this launcher started: SIGTERM, then SIGKILL after the grace period.")
  def stop(@arg(doc = "seconds to wait for a clean exit (default 50, above the app's HTTP_SHUTDOWN_TIMEOUT plus 15)") grace: Int = 50): Unit =
    finish(Launcher.stop(system(requireEnv = false, grace = grace)))

  @main(doc = "Stop, then start.")
  def restart(
    @arg(doc = "seconds to wait for the health check (default 60)") timeout: Int = 60,
    @arg(name = "no-compile", doc = "skip `sbt --client compile`") noCompile: Flag,
    @arg(name = "no-migrate", doc = "skip `migrate` before starting") noMigrate: Flag,
    @arg(doc = "seconds to wait for a clean exit (default 50)") grace: Int = 50
  ): Unit =
    finish(
      Launcher
        .restart(system(requireEnv = true, timeout = timeout, grace = grace), compileFirst = !noCompile.value, migrateFirst = !noMigrate.value)
    )

  @main(doc = "Apply pending database migrations: runs MigrateMain on the cached classpath with the app's .env.")
  def migrate(@arg(name = "no-compile", doc = "skip `sbt --client compile`") noCompile: Flag): Unit = {
    val sys      = system(requireEnv = true)
    val compiled = if (noCompile.value) Right(()) else sys.compile()
    compiled match {
      case Left(output) => finish(Launcher.Result(1, s"compile failed:\n$output"))
      case Right(())    =>
        sys.migrate() match {
          case Left(reason) => finish(Launcher.Result(1, s"migrations could not be run: $reason"))
          case Right(0)     => finish(Launcher.Result(0, "database schema is up to date"))
          case Right(code)  => finish(Launcher.Result(1, s"migrations failed (exit $code)"))
        }
    }
  }

  @main(doc = "Report whether the app is running. Exit 0 when it answers the health check.")
  def status(): Unit = finish(Launcher.status(system(requireEnv = false)))

  @main(doc = "Print the end of the app's log.")
  def logs(@arg(doc = "how many lines (default 50)") lines: Int = 50): Unit = {
    println(system(requireEnv = false).logTail(lines))
    sys.exit(0)
  }

  private def system(
    requireEnv: Boolean,
    timeout: Int = 60,
    grace: Int = 50
  ): RealSystem = {
    if (!os.isFile(root / "build.sbt")) fail("run this from the project root (no build.sbt here)")
    val envFile = root / ".env"
    val env     =
      if (os.isFile(envFile)) DotEnv.parse(os.read(envFile))
      else if (requireEnv) fail("no .env: `cp .env.sample .env` first")
      else Map.empty[String, String]
    val port = (sys.env ++ env).get("PORT").flatMap(_.toIntOption).getOrElse(9000)
    new RealSystem(root, env, port, timeout.seconds, grace.seconds)
  }

  private def finish(result: Launcher.Result): Unit = {
    println(result.message)
    sys.exit(result.exitCode)
  }

  private def fail(message: String): Nothing = {
    System.err.println(s"error: $message")
    sys.exit(2)
  }

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runEither(args.toIndexedSeq).left.foreach { usage =>
      System.err.println(usage)
      sys.exit(2)
    }
}
