package app

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.OptionConverters.*

object Processes {
  enum StopOutcome {
    case Stopped, Killed, NotRunning
  }
  export StopOutcome.*

  def alive(pid: Long): Boolean = ProcessHandle.of(pid).toScala.exists(_.isAlive)

  def stop(pid: Long, grace: FiniteDuration): StopOutcome =
    ProcessHandle.of(pid).toScala.filter(_.isAlive) match {
      case None         => NotRunning
      case Some(handle) =>
        handle.destroy()
        val exited = handle.onExit()
        try {
          exited.get(grace.toMillis, TimeUnit.MILLISECONDS)
          Stopped
        } catch {
          case _: TimeoutException =>
            handle.destroyForcibly()
            exited.get()
            Killed
        }
    }
}

object PidFile {
  def read(path: os.Path): Option[Long] =
    Option.when(os.isFile(path))(os.read(path).trim).flatMap(_.toLongOption)

  def write(path: os.Path, pid: Long): Unit = os.write.over(path, pid.toString, createFolders = true)

  def delete(path: os.Path): Unit = os.remove(path, checkExists = false)
}
