package app

import scala.concurrent.duration.*

class LauncherSuite extends munit.FunSuite {
  class Fake(
    var state: AppState,
    compileResult: Either[String, Unit] = Right(()),
    spawnResult: Either[String, Long] = Right(100L),
    health: Health.Outcome = Health.Ready,
    stopResult: Processes.StopOutcome = Processes.Stopped,
    migrateResult: Either[String, Int] = Right(0))
      extends Launcher.System {
    val actions                                 = scala.collection.mutable.ListBuffer.empty[String]
    def currentState(): AppState                = state
    def compile(): Either[String, Unit]         = { actions += "compile"; compileResult }
    def spawn(): Either[String, Long]           = { actions += "spawn"; spawnResult }
    def awaitHealthy(pid: Long): Health.Outcome = { actions += s"await $pid"; health }
    def migrate(): Either[String, Int]          = { actions += "migrate"; migrateResult }
    def stop(pid: Long): Processes.StopOutcome  = { actions += s"stop $pid"; state = AppState.Stopped; stopResult }
    def forgetPid(): Unit                       = { actions += "forget pid"; state = AppState.Stopped }
    def logTail(): String                       = "LOG TAIL"
    val url                                     = "http://localhost:9000/v1/health-check"
  }

  test("start on a running app does nothing and succeeds") {
    val sys = Fake(AppState.Running(5L))
    val r   = Launcher.start(sys, compileFirst = true)
    assertEquals(r.exitCode, 0)
    assert(r.message.contains("already running") && r.message.contains("5"), r.message)
    assertEquals(sys.actions.toList, Nil)
  }

  test("start when something else owns the port refuses and names the situation") {
    val sys = Fake(AppState.Foreign)
    val r   = Launcher.start(sys, compileFirst = true)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("not started by this launcher"), r.message)
    assertEquals(sys.actions.toList, Nil)
  }

  test("start compiles, migrates, spawns, waits, and reports the url and pid") {
    val sys = Fake(AppState.Stopped)
    val r   = Launcher.start(sys, compileFirst = true)
    assertEquals(r.exitCode, 0)
    assertEquals(sys.actions.toList, List("compile", "migrate", "spawn", "await 100"))
    assert(r.message.contains(sys.url) && r.message.contains("100"), r.message)
  }

  test("start can skip the compile") {
    val sys = Fake(AppState.Stopped)
    Launcher.start(sys, compileFirst = false)
    assertEquals(sys.actions.toList, List("migrate", "spawn", "await 100"))
  }

  test("a stale pid file is forgotten before starting") {
    val sys = Fake(AppState.Stale(3L))
    val r   = Launcher.start(sys, compileFirst = false)
    assertEquals(r.exitCode, 0)
    assertEquals(sys.actions.toList, List("forget pid", "migrate", "spawn", "await 100"))
  }

  test("a failed compile stops there and passes the compiler's message through") {
    val sys = Fake(AppState.Stopped, compileResult = Left("[error] Foo.scala:3: not found"))
    val r   = Launcher.start(sys, compileFirst = true)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("Foo.scala:3"), r.message)
    assertEquals(sys.actions.toList, List("compile"))
  }

  test("an app that dies before it is healthy fails with the log tail, and its pid is forgotten") {
    val sys = Fake(AppState.Stopped, health = Health.Died)
    val r   = Launcher.start(sys, compileFirst = false)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("LOG TAIL"), r.message)
    assertEquals(sys.actions.toList, List("migrate", "spawn", "await 100", "forget pid"))
  }

  private val pendingTail = "WARN Migrations$ - 6 pending DB migration(s): 1 - user auth\nERROR Application stopped"

  test("a failed migration stops the start before the app is spawned") {
    val sys = Fake(AppState.Stopped, migrateResult = Right(1))
    val r   = Launcher.start(sys, compileFirst = false)
    assertEquals(r.exitCode, 1)
    assertEquals(sys.actions.toList, List("migrate"))
    assert(r.message.contains("migrations failed (exit 1)") && r.message.contains("LOG TAIL"), r.message)
  }

  test("migrations that cannot run stop the start with the reason") {
    val sys = Fake(AppState.Stopped, migrateResult = Left("no MigrateMain among the compiled classes"))
    val r   = Launcher.start(sys, compileFirst = false)
    assertEquals(r.exitCode, 1)
    assertEquals(sys.actions.toList, List("migrate"))
    assert(r.message.contains("no MigrateMain"), r.message)
  }

  test("start can skip the migrations") {
    val sys = Fake(AppState.Stopped)
    Launcher.start(sys, compileFirst = false, migrateFirst = false)
    assertEquals(sys.actions.toList, List("spawn", "await 100"))
  }

  test("with --no-migrate, an app that died over pending migrations gets a hint to run them") {
    val sys = new Fake(AppState.Stopped, health = Health.Died) {
      override def logTail(): String = pendingTail
    }
    val r = Launcher.start(sys, compileFirst = false, migrateFirst = false)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("app.scala migrate"), r.message)
    assertEquals(sys.actions.toList, List("spawn", "await 100", "forget pid"))
  }

  test("an app that is still not healthy at the deadline is stopped, so no half-started process is left behind") {
    val sys = Fake(AppState.Stopped, health = Health.TimedOut)
    val r   = Launcher.start(sys, compileFirst = false)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("--timeout") && r.message.contains("LOG TAIL"), r.message)
    assertEquals(sys.actions.toList, List("migrate", "spawn", "await 100", "stop 100", "forget pid"))
  }

  test("stop on a running app stops it and forgets the pid") {
    val sys = Fake(AppState.Running(5L))
    val r   = Launcher.stop(sys)
    assertEquals(r.exitCode, 0)
    assertEquals(sys.actions.toList, List("stop 5", "forget pid"))
  }

  test("stop says so when it had to kill") {
    val sys = Fake(AppState.Starting(5L), stopResult = Processes.Killed)
    val r   = Launcher.stop(sys)
    assertEquals(r.exitCode, 0)
    assert(r.message.contains("killed"), r.message)
  }

  test("stop on a stopped app is a no-op that succeeds; a stale pid is cleaned up") {
    assertEquals(Launcher.stop(Fake(AppState.Stopped)).exitCode, 0)
    val stale = Fake(AppState.Stale(3L))
    assertEquals(Launcher.stop(stale).exitCode, 0)
    assertEquals(stale.actions.toList, List("forget pid"))
  }

  test("stop leaves a foreign app alone and says so") {
    val sys = Fake(AppState.Foreign)
    val r   = Launcher.stop(sys)
    assertEquals(r.exitCode, 1)
    assert(r.message.contains("not started by this launcher"), r.message)
    assertEquals(sys.actions.toList, Nil)
  }

  test("status exits 0 only for a running app and always says which state it found") {
    assertEquals(Launcher.status(Fake(AppState.Running(5L))).exitCode, 0)
    assertEquals(Launcher.status(Fake(AppState.Starting(5L))).exitCode, 1)
    assertEquals(Launcher.status(Fake(AppState.Stopped)).exitCode, 1)
    assertEquals(Launcher.status(Fake(AppState.Foreign)).exitCode, 1)
    assert(Launcher.status(Fake(AppState.Starting(5L))).message.contains("starting"))
    assert(Launcher.status(Fake(AppState.Stopped)).message.contains("not running"))
  }

  test("status on a stale pid file cleans it up and reports not running") {
    val sys = Fake(AppState.Stale(3L))
    val r   = Launcher.status(sys)
    assertEquals(r.exitCode, 1)
    assertEquals(sys.actions.toList, List("forget pid"))
  }

  test("restart stops a running app and starts again") {
    val sys = Fake(AppState.Running(5L))
    val r   = Launcher.restart(sys, compileFirst = true)
    assertEquals(r.exitCode, 0)
    assertEquals(sys.actions.toList, List("stop 5", "forget pid", "compile", "migrate", "spawn", "await 100"))
  }

  test("restart of a stopped app is just a start") {
    val sys = Fake(AppState.Stopped)
    Launcher.restart(sys, compileFirst = false)
    assertEquals(sys.actions.toList, List("migrate", "spawn", "await 100"))
  }
}
