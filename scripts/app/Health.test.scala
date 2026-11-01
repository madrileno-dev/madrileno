package app

class HealthSuite extends munit.FunSuite {
  private def probeAnsweringAt(n: Int): () => Boolean = {
    var calls = 0
    () => { calls += 1; calls >= n }
  }

  test("is Ready as soon as the probe answers") {
    var pauses  = 0
    val outcome = Health.await(probe = probeAnsweringAt(3), alive = () => true, attempts = 10, pause = () => pauses += 1)
    assertEquals(outcome, Health.Ready)
    assertEquals(pauses, 2)
  }

  test("is Died when the process is gone before the probe answers") {
    val outcome = Health.await(probe = () => false, alive = () => false, attempts = 10, pause = () => ())
    assertEquals(outcome, Health.Died)
  }

  test("is TimedOut after the attempts run out with the process still alive") {
    var pauses  = 0
    val outcome = Health.await(probe = () => false, alive = () => true, attempts = 4, pause = () => pauses += 1)
    assertEquals(outcome, Health.TimedOut)
    assertEquals(pauses, 3, "pauses sit between probes, none after the last one")
  }

  test("a probe that answers on the last attempt is still Ready") {
    val outcome = Health.await(probe = probeAnsweringAt(4), alive = () => true, attempts = 4, pause = () => ())
    assertEquals(outcome, Health.Ready)
  }
}
