package madrileno.ticket

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.time.Duration
import scala.concurrent.duration.*

class TicketConfigSpec extends AnyWordSpec with Matchers {

  private def load(holdDuration: String) =
    ConfigSource.string(s"ticket { hold-duration = $holdDuration }").at("ticket").load[TicketConfig]

  "TicketConfig" should {
    "load a valid hold duration" in {
      load("15 minutes") shouldBe Right(TicketConfig(15.minutes))
    }

    "expose the hold duration as java.time.Duration" in {
      TicketConfig(90.seconds).holdDurationJava shouldBe Duration.ofSeconds(90)
    }

    "reject a zero hold duration with a clear message" in {
      val result = load("0 seconds")
      result.isLeft shouldBe true
      result.left.toOption.get.prettyPrint() should include("ticket.hold-duration must be greater than zero")
    }

    "reject a negative hold duration" in {
      load("-5 minutes").isLeft shouldBe true
    }

    "default to 10 minutes in application.conf" in {
      ConfigSource.resources("application.conf").at("ticket").load[TicketConfig] shouldBe Right(TicketConfig(10.minutes))
    }
  }
}
