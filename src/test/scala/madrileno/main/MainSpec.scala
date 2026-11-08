package madrileno.main

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import scala.concurrent.duration.*

class MainSpec extends AnyWordSpec with Matchers {

  "Main.shutdownHookTimeout" should {
    "stop waiting just inside the drain window plus 15 seconds" in {
      Main.shutdownHookTimeout(ConfigSource.string("http.shutdown-timeout = 30s")) shouldBe 44.seconds
    }

    "follow a longer drain window" in {
      Main.shutdownHookTimeout(ConfigSource.string("http.shutdown-timeout = 2m")) shouldBe 134.seconds
    }

    "not cut the process short when the drain window doesn't parse, so the startup error gets logged" in {
      Main.shutdownHookTimeout(ConfigSource.string("http.shutdown-timeout = soon")) shouldBe Duration.Inf
    }

    "not cut the process short when the drain window is missing" in {
      Main.shutdownHookTimeout(ConfigSource.string("http {}")) shouldBe Duration.Inf
    }
  }
}
