package app

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class HttpProbeSuite extends munit.FunSuite {
  private def server(statusLine: String)(check: (Int, AtomicBoolean) => Unit): Unit = {
    val socket       = new ServerSocket(0)
    val clientClosed = new AtomicBoolean(false)
    val thread       = new Thread(() => {
      val conn = socket.accept()
      val in   = conn.getInputStream
      val out  = conn.getOutputStream
      val buf  = new Array[Byte](4096)
      in.read(buf)
      out.write(s"$statusLine\r\nContent-Length: 2\r\n\r\nok".getBytes)
      out.flush()
      conn.setSoTimeout(2000)
      clientClosed.set(scala.util.Try(in.read()).toOption.contains(-1))
      conn.close()
    })
    thread.setDaemon(true)
    thread.start()
    try check(socket.getLocalPort, clientClosed)
    finally socket.close()
  }

  test("a 200 answer is healthy") {
    server("HTTP/1.1 200 OK") { (port, _) =>
      assert(HttpProbe.ok(s"http://localhost:$port/v1/health-check"))
    }
  }

  test("a non-200 answer is not healthy") {
    server("HTTP/1.1 503 Service Unavailable") { (port, _) =>
      assert(!HttpProbe.ok(s"http://localhost:$port/v1/health-check"))
    }
  }

  test("nothing listening is not healthy") {
    val free = { val s = new ServerSocket(0); val p = s.getLocalPort; s.close(); p }
    assert(!HttpProbe.ok(s"http://localhost:$free/v1/health-check"))
  }

  test("the probe closes its connection, so the app's graceful shutdown has nothing to wait for") {
    server("HTTP/1.1 200 OK") { (port, clientClosed) =>
      HttpProbe.ok(s"http://localhost:$port/v1/health-check")
      Thread.sleep(300)
      assert(clientClosed.get(), "server still saw an open connection after the probe returned")
    }
  }
}
