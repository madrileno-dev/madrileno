package madrileno.utils.http

import com.comcast.ip4s.{Cidr, IpAddress}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RateLimiterRuntimeSpec extends AnyWordSpec with Matchers {

  private def cidr(value: String): Cidr[IpAddress] = Cidr.fromString(value).getOrElse(fail(s"bad fixture $value"))

  "RateLimiterRuntime.parseTrustedProxies" should {
    "return nothing for a blank list" in {
      RateLimiterRuntime.parseTrustedProxies("") shouldBe Nil
      RateLimiterRuntime.parseTrustedProxies(" , ,") shouldBe Nil
    }

    "keep CIDR entries as given" in {
      RateLimiterRuntime.parseTrustedProxies("10.0.0.0/8, 2001:db8::/32") shouldBe List(cidr("10.0.0.0/8"), cidr("2001:db8::/32"))
    }

    "widen a bare IPv4 address to a /32 and a bare IPv6 address to a /128" in {
      RateLimiterRuntime.parseTrustedProxies("192.168.1.10,::1") shouldBe List(cidr("192.168.1.10/32"), cidr("::1/128"))
    }

    "fail fast on an entry that is neither a CIDR nor an address" in {
      val error = intercept[IllegalArgumentException](RateLimiterRuntime.parseTrustedProxies("10.0.0.0/8, proxy.internal"))
      error.getMessage should include("'proxy.internal'")
    }
  }
}
