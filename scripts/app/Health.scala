package app

object Health {
  enum Outcome {
    case Ready, Died, TimedOut
  }
  export Outcome.*

  def await(
    probe: () => Boolean,
    alive: () => Boolean,
    attempts: Int,
    pause: () => Unit
  ): Outcome = {
    @annotation.tailrec
    def loop(remaining: Int): Outcome =
      if (probe()) Ready
      else if (!alive()) Died
      else if (remaining <= 1) TimedOut
      else {
        pause()
        loop(remaining - 1)
      }
    loop(attempts)
  }
}
