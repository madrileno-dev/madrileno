package app

import scala.concurrent.duration.*

class ProcessesSuite extends munit.FunSuite {
  private def spawn(cmd: String*): os.SubProcess = os.proc(cmd).spawn(stdout = os.Pipe, stderr = os.Pipe)

  test("a sleeping child is alive until stopped, and stop is Stopped when it exits on SIGTERM") {
    val child = spawn("sleep", "60")
    val pid   = child.wrapped.pid()
    assert(Processes.alive(pid))
    assertEquals(Processes.stop(pid, grace = 5.seconds), Processes.Stopped)
    assert(!Processes.alive(pid))
  }

  test("a child that ignores SIGTERM is killed after the grace period") {
    val child = spawn("bash", "-c", "trap '' TERM; sleep 60")
    val pid   = child.wrapped.pid()
    Thread.sleep(200)
    assertEquals(Processes.stop(pid, grace = 300.millis), Processes.Killed)
    assert(!Processes.alive(pid))
  }

  test("stopping a pid that does not exist is NotRunning") {
    val child = spawn("true")
    child.waitFor()
    Thread.sleep(50)
    assertEquals(Processes.stop(child.wrapped.pid(), grace = 1.second), Processes.NotRunning)
  }

  test("the pid file round-trips and reads as None when missing or garbage") {
    val dir  = os.temp.dir()
    val file = dir / "app.pid"
    assertEquals(PidFile.read(file), None)
    PidFile.write(file, 4242L)
    assertEquals(PidFile.read(file), Some(4242L))
    os.write.over(file, "not a pid")
    assertEquals(PidFile.read(file), None)
  }
}
