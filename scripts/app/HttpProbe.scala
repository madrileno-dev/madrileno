package app

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetSocketAddress, Socket, URI}
import scala.util.{Try, Using}

object HttpProbe {
  def ok(url: String, timeoutMillis: Int = 2000): Boolean = {
    val uri  = URI.create(url)
    val port = if (uri.getPort == -1) 80 else uri.getPort
    val path = if (uri.getRawPath.isEmpty) "/" else uri.getRawPath
    Using(new Socket()) { socket =>
      socket.connect(new InetSocketAddress(uri.getHost, port), timeoutMillis)
      socket.setSoTimeout(timeoutMillis)
      socket.getOutputStream.write(s"GET $path HTTP/1.0\r\nHost: ${uri.getHost}\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
      socket.getOutputStream.flush()
      val statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream, "US-ASCII")).readLine()
      Option(statusLine).exists(_.split(" ").lift(1).contains("200"))
    }.getOrElse(false)
  }
}
