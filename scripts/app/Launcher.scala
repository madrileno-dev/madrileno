package app

object Launcher {
  final case class Result(exitCode: Int, message: String)

  trait System {
    def currentState(): AppState
    def compile(): Either[String, Unit]
    def spawn(): Either[String, Long]
    def awaitHealthy(pid: Long): Health.Outcome
    def stop(pid: Long): Processes.StopOutcome
    def forgetPid(): Unit
    def migrate(): Either[String, Int]
    def logTail(): String
    def url: String
  }

  private val foreign =
    "an app answers the health check but was not started by this launcher (a developer's `~reStart` loop?): use it, or stop it yourself"

  def start(
    sys: System,
    compileFirst: Boolean,
    migrateFirst: Boolean = true
  ): Result =
    sys.currentState() match {
      case AppState.Running(pid)  => Result(0, s"app already running (pid $pid) at ${sys.url}")
      case AppState.Starting(pid) => Result(1, s"app is still starting (pid $pid); `app status` to check, `app stop` to give up")
      case AppState.Foreign       => Result(1, foreign)
      case AppState.Stale(_)      =>
        sys.forgetPid()
        launch(sys, compileFirst, migrateFirst)
      case AppState.Stopped => launch(sys, compileFirst, migrateFirst)
    }

  private def launch(
    sys: System,
    compileFirst: Boolean,
    migrateFirst: Boolean
  ): Result = {
    val compiled = if (compileFirst) sys.compile() else Right(())
    compiled match {
      case Left(output) => Result(1, s"compile failed:\n$output")
      case Right(())    =>
        val migrated = if (migrateFirst) migrate(sys) else None
        migrated.getOrElse(spawnAndAwait(sys))
    }
  }

  private def migrate(sys: System): Option[Result] =
    sys.migrate() match {
      case Left(reason) => Some(Result(1, s"migrations could not be run: $reason"))
      case Right(0)     => None
      case Right(code)  => Some(Result(1, s"migrations failed (exit $code). Last log lines:\n${sys.logTail()}"))
    }

  private def spawnAndAwait(sys: System): Result =
    sys.spawn() match {
      case Left(reason) => Result(1, s"could not start the app: $reason")
      case Right(pid)   =>
        sys.awaitHealthy(pid) match {
          case Health.Ready => Result(0, s"app ready at ${sys.url} (pid $pid)")
          case Health.Died  =>
            sys.forgetPid()
            died(sys.logTail())
          case Health.TimedOut =>
            sys.stop(pid)
            sys.forgetPid()
            Result(
              1,
              s"app did not answer the health check in time and was stopped (raise --timeout if the machine is slow). Last log lines:\n${sys.logTail()}"
            )
        }
    }

  private def pendingMigrations(logTail: String): Boolean = logTail.contains("pending DB migration")

  private def died(logTail: String): Result = {
    val hint =
      if (pendingMigrations(logTail)) " The database has pending migrations: run `./scripts/app.scala migrate` first."
      else ""
    Result(1, s"app exited before it became healthy.$hint Last log lines:\n$logTail")
  }

  def stop(sys: System): Result =
    sys.currentState() match {
      case AppState.Running(pid)  => stopPid(sys, pid)
      case AppState.Starting(pid) => stopPid(sys, pid)
      case AppState.Stale(_)      =>
        sys.forgetPid()
        Result(0, "app not running (removed a stale pid file)")
      case AppState.Foreign => Result(1, foreign)
      case AppState.Stopped => Result(0, "app not running")
    }

  private def stopPid(sys: System, pid: Long): Result = {
    val outcome = sys.stop(pid)
    sys.forgetPid()
    outcome match {
      case Processes.Stopped    => Result(0, s"app stopped (pid $pid)")
      case Processes.Killed     => Result(0, s"app did not exit within the grace period and was killed (pid $pid)")
      case Processes.NotRunning => Result(0, s"app was already gone (pid $pid)")
    }
  }

  def status(sys: System): Result =
    sys.currentState() match {
      case AppState.Running(pid)  => Result(0, s"app running (pid $pid) at ${sys.url}")
      case AppState.Starting(pid) => Result(1, s"app starting (pid $pid): not answering the health check yet")
      case AppState.Stale(_)      =>
        sys.forgetPid()
        Result(1, "app not running (removed a stale pid file)")
      case AppState.Foreign => Result(1, foreign)
      case AppState.Stopped => Result(1, "app not running")
    }

  def restart(
    sys: System,
    compileFirst: Boolean,
    migrateFirst: Boolean = true
  ): Result = {
    val stopped = stop(sys)
    if (stopped.exitCode != 0) stopped else start(sys, compileFirst, migrateFirst)
  }
}
