package app

class AppStateSuite extends munit.FunSuite {
  private val alive: Long => Boolean = _ == 42L

  test("no pid file and no health answer is Stopped") {
    assertEquals(AppState.assess(pidFile = None, alive, healthy = false), AppState.Stopped)
  }

  test("no pid file but a health answer is Foreign: something we did not start owns the port") {
    assertEquals(AppState.assess(pidFile = None, alive, healthy = true), AppState.Foreign)
  }

  test("a live pid that answers the health check is Running") {
    assertEquals(AppState.assess(pidFile = Some(42L), alive, healthy = true), AppState.Running(42L))
  }

  test("a live pid that does not answer yet is Starting") {
    assertEquals(AppState.assess(pidFile = Some(42L), alive, healthy = false), AppState.Starting(42L))
  }

  test("a dead pid with no health answer is Stale") {
    assertEquals(AppState.assess(pidFile = Some(7L), alive, healthy = false), AppState.Stale(7L))
  }

  test("a dead pid while the port answers is Foreign, not Running") {
    assertEquals(AppState.assess(pidFile = Some(7L), alive, healthy = true), AppState.Foreign)
  }
}
