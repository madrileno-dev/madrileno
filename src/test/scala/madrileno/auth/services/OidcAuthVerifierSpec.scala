package madrileno.auth.services

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import madrileno.auth.OidcProviderConfig
import madrileno.auth.domain.{ExternalAuthToken, Provider}
import madrileno.support.TestCacheRuntime
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{ResponseStub, WebSocketStreamBackendStub}
import sttp.model.StatusCode

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.Base64

class OidcAuthVerifierSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {
  private val issuer   = "https://issuer.example.com"
  private val audience = "madrileno-client"
  private val provider = Provider("example")

  private val (publicKey, privateKey) = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    (pair.getPublic, pair.getPrivate) match {
      case (pub: RSAPublicKey, priv: RSAPrivateKey) => (pub, priv)
      case other                                    => throw new IllegalStateException(s"expected RSA key pair, got $other")
    }
  }

  private def base64Url(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def jwksJson(kid: String): String = {
    val n = base64Url(publicKey.getModulus.toByteArray.dropWhile(_ == 0))
    val e = base64Url(publicKey.getPublicExponent.toByteArray)
    s"""{"keys":[{"kid":"$kid","kty":"RSA","n":"$n","e":"$e"},{"kid":"ec-key","kty":"EC"}]}"""
  }

  private def discoveryJson(advertisedIssuer: String, jwksUri: String): String =
    s"""{"issuer":"$advertisedIssuer","jwks_uri":"$jwksUri"}"""

  private def routingBackend(calls: Ref[IO, List[String]])(respond: PartialFunction[String, String]): WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    WebSocketStreamBackendStub[IO, Fs2Streams[IO]](new CatsMonadAsyncError[IO]()).whenAnyRequest
      .thenRespondF { request =>
        val path = request.uri.path.mkString("/", "/", "")
        calls.update(_ :+ path) *>
          IO.pure(respond.lift(path).fold(ResponseStub.adjust("not found", StatusCode.NotFound))(body => ResponseStub.adjust(body)))
      }

  private def signed(kid: String): ExternalAuthToken = {
    val now = Instant.now()
    ExternalAuthToken(
      JWT
        .create()
        .withKeyId(kid)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject("subject-1")
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(300))
        .sign(Algorithm.RSA256(publicKey, privateKey))
    )
  }

  private def config(jwksUri: Option[String]): OidcProviderConfig =
    OidcProviderConfig(issuer = issuer, audience = audience, jwksUri = jwksUri)

  "OidcAuthVerifier" should {
    "discover the JWKS endpoint and verify a token against it" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend = routingBackend(calls) {
                    case "/.well-known/openid-configuration" => discoveryJson(issuer, s"$issuer/keys")
                    case "/keys"                             => jwksJson("kid-1")
                  }
        verifier = OidcAuthVerifier(provider, config(None), backend, TestCacheRuntime.unbounded)
        first  <- verifier.verifyToken(signed("kid-1"))
        second <- verifier.verifyToken(signed("kid-1"))
        seen   <- calls.get
      } yield {
        first.map(_.providerUserId.unwrap) shouldBe Right("subject-1")
        second.isRight shouldBe true
        seen shouldBe List("/.well-known/openid-configuration", "/keys")
      }
    }

    "use a configured jwks-uri without touching discovery" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend  = routingBackend(calls) { case "/static-keys" => jwksJson("kid-1") }
        verifier = OidcAuthVerifier(provider, config(Some(s"$issuer/static-keys")), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-1"))
        seen   <- calls.get
      } yield {
        result.isRight shouldBe true
        seen shouldBe List("/static-keys")
      }
    }

    "reject a token whose kid is absent from the JWKS" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend  = routingBackend(calls) { case "/static-keys" => jwksJson("kid-1") }
        verifier = OidcAuthVerifier(provider, config(Some(s"$issuer/static-keys")), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-other"))
      } yield result.left.map(_.getMessage) shouldBe Left("unknown JWKS key id 'kid-other'")
    }

    "fail verification when discovery advertises a different issuer" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend = routingBackend(calls) {
                    case "/.well-known/openid-configuration" => discoveryJson("https://evil.example.com", s"$issuer/keys")
                    case "/keys"                             => jwksJson("kid-1")
                  }
        verifier = OidcAuthVerifier(provider, config(None), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-1"))
      } yield result.left.map(_.getMessage).left.toOption.getOrElse("") should include("issuer mismatch")
    }

    "fail verification when discovery returns an unparseable jwks_uri" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend  = routingBackend(calls) { case "/.well-known/openid-configuration" => discoveryJson(issuer, "http://host:abc") }
        verifier = OidcAuthVerifier(provider, config(None), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-1"))
      } yield result.left.map(_.getMessage).left.toOption.getOrElse("") should include("invalid jwks_uri")
    }

    "fail verification when the JWKS endpoint answers with an error" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend  = routingBackend(calls) { case "/.well-known/openid-configuration" => discoveryJson(issuer, s"$issuer/keys") }
        verifier = OidcAuthVerifier(provider, config(None), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-1"))
      } yield result.isLeft shouldBe true
    }

    "refuse construction with an invalid configured jwks-uri" in {
      for {
        calls <- Ref.of[IO, List[String]](Nil)
        backend  = routingBackend(calls)(PartialFunction.empty)
        verifier = OidcAuthVerifier(provider, config(Some("http://host:abc")), backend, TestCacheRuntime.unbounded)
        result <- verifier.verifyToken(signed("kid-1"))
      } yield result.left.map(_.getMessage).left.toOption.getOrElse("") should include("invalid jwks-uri")
    }

    "refuse an empty audience" in {
      IO(
        OidcAuthVerifier(
          provider,
          config(None).copy(audience = " , "),
          routingBackend(Ref.unsafe(Nil))(PartialFunction.empty),
          TestCacheRuntime.unbounded
        )
      ).attempt
        .asserting(_.left.map(_.getMessage).left.toOption.getOrElse("") should include("non-empty audience"))
    }
  }
}
