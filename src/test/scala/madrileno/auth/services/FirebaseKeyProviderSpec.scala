package madrileno.auth.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{ResponseStub, WebSocketStreamBackendStub}
import sttp.model.{Header, StatusCode}

class FirebaseKeyProviderSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val certificatePem: String =
    """-----BEGIN CERTIFICATE-----
      |MIIDFTCCAf2gAwIBAgIUco1KL8llG9L6FfbVXzvJMBQTtyIwDQYJKoZIhvcNAQEL
      |BQAwGTEXMBUGA1UEAwwObWFkcmlsZW5vLXRlc3QwIBcNMjYxMDEzMTQ0MTIyWhgP
      |MjEyNjA5MTkxNDQxMjJaMBkxFzAVBgNVBAMMDm1hZHJpbGVuby10ZXN0MIIBIjAN
      |BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApnV9LFLqEeiOTqdtvHXkTU/3tNCQ
      |jUa3KY8P42QiD6Y53DLuPVhdJDUkj86FJEru3h2xoQaibocSLF5qbLjCbEVAg2Vc
      |pJeWXT32RoWU8DiILMb4f98J4hVA2eMOy7AAbDa1k0OjHL9/MTZQHTVZQOGOf8Bo
      |23DYHm4UqN8Bvg93+PvAgDHdC9rP2997E1wkiyImFImBwTxQbITv+YbiI+sIbI2Y
      |GCaAYZcdqeUJM7Ru0EQWzUna1ec5HvoPRyY3niUfbyzAaSNq2kOQKCbUBuvPrKui
      |3LbczhgwtBfrf2t9kIW4GORdVr/9HKF7osBZnVnOADFpVPxCFxgPRa01AQIDAQAB
      |o1MwUTAdBgNVHQ4EFgQUQas8BDT4/EvmHtpGjMSmk6qLDNMwHwYDVR0jBBgwFoAU
      |Qas8BDT4/EvmHtpGjMSmk6qLDNMwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0B
      |AQsFAAOCAQEAJ9Fpxz25/lQtrWL1y3PKqSKKBBsG3A5rMoU4DCEIvClKnfT7ZNiI
      |uJFhbIoha6trCq3kBZNCDtfWiozyVtUVQUnCpBemqFd3u3B6e8dx4UIbTeTzbYJw
      |9pnKHgmEHFn0IFVY46r2bZHceS2D/wrVuu+yActFzCI12/gyxyxQnluY8N2T+vb2
      |DVNuwguVire6nxYjwzp6PbBF2QOsnru6gYnEVTytXptSw+USn6Yvus5AufavPfO6
      |9k6LqCItTsUQyH0x3blisYSXFz7oq+fKJJT0rRbe+mdfKPGFSueFRhbtZFpmVaFs
      |b9qhXTnwEJy5DtqtIfV0RWuXmMv+g6Y/dg==
      |-----END CERTIFICATE-----
      |""".stripMargin

  private def certsJson(kids: String*): String =
    kids.map(kid => s""""$kid": ${io.circe.Json.fromString(certificatePem).noSpaces}""").mkString("{", ",", "}")

  private def backend(
    calls: Ref[IO, Int],
    body: String,
    headers: Header*
  ): WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    WebSocketStreamBackendStub[IO, Fs2Streams[IO]](new CatsMonadAsyncError[IO]()).whenAnyRequest
      .thenRespondF(_ => calls.update(_ + 1).as(ResponseStub.adjust(body, StatusCode.Ok, headers.toList)))

  "FirebaseKeyProvider.keyFor" should {
    "parse the PEM certificate for the requested kid" in {
      for {
        calls <- Ref.of[IO, Int](0)
        provider = new FirebaseKeyProvider(backend(calls, certsJson("kid-a", "kid-b")))
        key <- provider.keyFor("kid-b")
      } yield key.getAlgorithm shouldBe "RSA"
    }

    "serve subsequent lookups from the cache while Cache-Control max-age is in effect" in {
      for {
        calls <- Ref.of[IO, Int](0)
        provider = new FirebaseKeyProvider(backend(calls, certsJson("kid-a"), Header("Cache-Control", "public, max-age=3600")))
        _ <- provider.keyFor("kid-a")
        _ <- provider.keyFor("kid-a")
        n <- calls.get
      } yield n shouldBe 1
    }

    "refetch once the cached certificates have expired" in {
      for {
        calls <- Ref.of[IO, Int](0)
        provider = new FirebaseKeyProvider(backend(calls, certsJson("kid-a"), Header("Cache-Control", "max-age=0")))
        _ <- provider.keyFor("kid-a")
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(1100).millis)
        _ <- provider.keyFor("kid-a")
        n <- calls.get
      } yield n shouldBe 2
    }

    "fail with the kid in the message when it is not in the fetched set" in {
      for {
        calls <- Ref.of[IO, Int](0)
        provider = new FirebaseKeyProvider(backend(calls, certsJson("kid-a")))
        result <- provider.keyFor("kid-zzz").attempt
      } yield result.left.map(_.getMessage) shouldBe Left("unknown Firebase signing key id 'kid-zzz'")
    }

    "surface an HTTP failure from the certificate endpoint" in {
      val failing = WebSocketStreamBackendStub[IO, Fs2Streams[IO]](new CatsMonadAsyncError[IO]()).whenAnyRequest
        .thenRespondF(_ => IO.pure(ResponseStub.adjust("nope", StatusCode.ServiceUnavailable)))
      new FirebaseKeyProvider(failing).keyFor("kid-a").attempt.asserting(_.isLeft shouldBe true)
    }
  }
}
