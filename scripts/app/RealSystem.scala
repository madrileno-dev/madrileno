package app

import java.lang.ProcessBuilder.Redirect
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

final class RealSystem(
  root: os.Path,
  env: Map[String, String],
  port: Int,
  timeout: FiniteDuration,
  grace: FiniteDuration)
    extends Launcher.System {
  private val stateDir  = root / "target" / "app"
  private val pidFile   = stateDir / "app.pid"
  private val stdoutLog = stateDir / "app.out.log"
  private val logsDir   = root / "logs"

  val url: String = s"http://localhost:$port/v1/health-check"

  private def healthy(): Boolean = HttpProbe.ok(url)

  def currentState(): AppState = AppState.assess(PidFile.read(pidFile), Processes.alive, healthy())

  def compile(): Either[String, Unit] = {
    val result = os.proc("sbt", "--client", "compile").call(cwd = root, check = false, mergeErrIntoOut = true, stdout = os.Pipe)
    if (result.exitCode == 0) Right(()) else Left(result.out.lines().takeRight(40).mkString("\n"))
  }

  def spawn(): Either[String, Long] =
    for {
      classpath <- Classpath.read(root)
      mainClass <- MainClass.find(Classpath.classesDir(classpath))
    } yield {
      os.makeDir.all(stateDir)
      val builder = new ProcessBuilder(Launch.command(RealSystem.java, classpath, mainClass)*)
        .directory(root.toIO)
        .redirectErrorStream(true)
        .redirectOutput(Redirect.appendTo(stdoutLog.toIO))
      val processEnv = builder.environment()
      Launch.environment(sys.env, env).foreach { case (k, v) => processEnv.put(k, v) }
      val process = builder.start()
      PidFile.write(pidFile, process.pid())
      process.pid()
    }

  def migrate(): Either[String, Int] =
    for {
      classpath <- Classpath.read(root)
      mainClass <- MainClass.migrate(Classpath.classesDir(classpath))
    } yield os
      .proc(Launch.command(RealSystem.java, classpath, mainClass))
      .call(cwd = root, env = Launch.environment(sys.env, env), check = false, stdout = os.Inherit, stderr = os.Inherit)
      .exitCode

  def awaitHealthy(pid: Long): Health.Outcome =
    Health.await(
      probe = () => healthy(),
      alive = () => Processes.alive(pid),
      attempts = timeout.toSeconds.toInt.max(1),
      pause = () => Thread.sleep(1000)
    )

  def stop(pid: Long): Processes.StopOutcome = Processes.stop(pid, grace)

  def forgetPid(): Unit = PidFile.delete(pidFile)

  def logTail(): String = logTail(50)

  def logTail(lines: Int): String = {
    val appLog = Option.when(os.isDir(logsDir))(os.list(logsDir).filter(_.ext == "log")).getOrElse(Nil).sortBy(os.mtime).lastOption
    appLog.orElse(Option.when(os.isFile(stdoutLog))(stdoutLog)) match {
      case Some(file) => s"($file)\n" + os.read.lines(file).takeRight(lines).mkString("\n")
      case None       => "(no log written yet)"
    }
  }
}

object RealSystem {
  val java: String = ProcessHandle.current().info().command().toScala.getOrElse("java")
}
