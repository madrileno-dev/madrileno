package madrileno.auction.gateways

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.client4.{ResponseException, SttpClientException, basicRequest}
import sttp.model.{ResponseMetadata, StatusCode, Uri}

import java.io.IOException
import java.util.concurrent.TimeoutException

class VivinoGatewayTransientSpec extends AnyWordSpec with Matchers {

  private val request = basicRequest.get(Uri.unsafeParse("https://vivino.example/api"))

  "VivinoGateway.isTransient" should {
    "retry on timeouts and I/O failures" in {
      VivinoGateway.isTransient(new TimeoutException("slow")) shouldBe true
      VivinoGateway.isTransient(new IOException("reset")) shouldBe true
    }

    "retry on sttp connection and read failures" in {
      VivinoGateway.isTransient(new SttpClientException.ConnectException(request, new IOException("refused"))) shouldBe true
      VivinoGateway.isTransient(new SttpClientException.ReadException(request, new IOException("eof"))) shouldBe true
    }

    "not retry when the response was received but could not be handled" in {
      VivinoGateway.isTransient(
        new SttpClientException.ResponseHandlingException(
          request,
          ResponseException.UnexpectedStatusCode("bad body", ResponseMetadata(StatusCode.InternalServerError, "", Nil))
        )
      ) shouldBe false
    }

    "not retry on unrelated errors" in {
      VivinoGateway.isTransient(new IllegalStateException("bug")) shouldBe false
    }
  }
}
