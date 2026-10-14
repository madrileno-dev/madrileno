package madrileno.utils.task

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.otel4s.trace.{SpanContext, TraceFlags, TraceState}
import scodec.bits.ByteVector

class TraceParentSpec extends AnyWordSpec with Matchers {

  private val context = SpanContext(
    traceId = ByteVector.fromValidHex("4bf92f3577b34da6a3ce929d0e0e4736"),
    spanId = ByteVector.fromValidHex("00f067aa0ba902b7"),
    traceFlags = TraceFlags.Sampled,
    traceState = TraceState.empty,
    remote = false
  )

  "TraceParent" should {
    "render the W3C traceparent header" in {
      TraceParent.of(context) shouldBe "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }

    "parse back into a remote context with the same ids and flags" in {
      val parsed = TraceParent.parse(TraceParent.of(context))
      parsed.map(_.traceIdHex) shouldBe Some(context.traceIdHex)
      parsed.map(_.spanIdHex) shouldBe Some(context.spanIdHex)
      parsed.map(_.isSampled) shouldBe Some(true)
      parsed.map(_.isRemote) shouldBe Some(true)
    }

    "reject malformed input" in {
      TraceParent.parse("") shouldBe None
      TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7") shouldBe None
      TraceParent.parse("00-zz92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") shouldBe None
      TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-xx") shouldBe None
    }

    "reject all-zero ids" in {
      TraceParent.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01") shouldBe None
      TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01") shouldBe None
    }
  }
}
