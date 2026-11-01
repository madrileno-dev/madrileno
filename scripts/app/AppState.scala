package app

enum AppState {
  case Running(pid: Long)
  case Starting(pid: Long)
  case Stale(pid: Long)
  case Foreign
  case Stopped
}

object AppState {
  def assess(
    pidFile: Option[Long],
    alive: Long => Boolean,
    healthy: Boolean
  ): AppState =
    pidFile match {
      case Some(pid) if alive(pid) => if (healthy) Running(pid) else Starting(pid)
      case Some(pid)               => if (healthy) Foreign else Stale(pid)
      case None                    => if (healthy) Foreign else Stopped
    }
}
