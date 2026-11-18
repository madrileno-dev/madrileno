package madrileno.support

import cats.effect.unsafe.IORuntime

object TestRun {

  def start(): Unit = {
    val thread = Thread.currentThread
    val caller = thread.getContextClassLoader
    thread.setContextClassLoader(getClass.getClassLoader)
    try {
      val _ = IORuntime.global
    } finally thread.setContextClassLoader(caller)
  }

  def finish(): Unit =
    try TestPostgres.stopShared()
    finally IORuntime.global.shutdown()
}
