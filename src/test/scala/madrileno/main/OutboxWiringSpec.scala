package madrileno.main

import madrileno.support.TestApplicationLoader
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class OutboxWiringSpec extends AnyFunSpec with Matchers with TestApplicationLoader {

  describe("ApplicationLoader outbox subscriptions") {
    it("registers the consumer of every subscribing production module") {
      val consumers = application.outboxSubscriptions.map(_.consumer)
      consumers should contain("auth")
      // scripts:auction-block-start
      consumers should contain("auction")
      // scripts:auction-block-end
    }
  }
}
