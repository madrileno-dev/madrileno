package madrileno.auth.services

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import madrileno.auth.domain.ExternalAuthToken
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant

class FirebaseServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {
  private val projectId = "madrileno-test"

  private val (publicKey, privateKey) = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    (pair.getPublic, pair.getPrivate) match {
      case (pub: RSAPublicKey, priv: RSAPrivateKey) => (pub, priv)
      case other                                    => throw new IllegalStateException(s"expected RSA key pair, got $other")
    }
  }

  private val service = new FirebaseService(projectId, _ => IO.pure(publicKey))

  private def token(authTime: Option[Long], subject: String = "firebase-uid"): ExternalAuthToken = {
    val now     = Instant.now()
    val builder = JWT
      .create()
      .withKeyId("kid-1")
      .withIssuer(s"https://securetoken.google.com/$projectId")
      .withAudience(projectId)
      .withSubject(subject)
      .withIssuedAt(now)
      .withExpiresAt(now.plusSeconds(300))
    authTime.foreach(t => builder.withClaim("auth_time", java.lang.Long.valueOf(t)))
    ExternalAuthToken(builder.sign(Algorithm.RSA256(publicKey, privateKey)))
  }

  "FirebaseService.verifyToken" should {
    "accept a token whose auth_time is in the past" in {
      service.verifyToken(token(Some(Instant.now().getEpochSecond - 60))).asserting {
        case Right(verified) => verified.providerUserId.unwrap shouldBe "firebase-uid"
        case Left(t)         => fail(s"expected Right, got Left($t)")
      }
    }

    "accept a token whose auth_time is within the clock-skew leeway" in {
      service.verifyToken(token(Some(Instant.now().getEpochSecond + 10))).asserting(_.isRight shouldBe true)
    }

    "reject a token whose auth_time is further in the future than the leeway" in {
      service.verifyToken(token(Some(Instant.now().getEpochSecond + 600))).asserting {
        case Left(t)  => t.getMessage should include("auth_time")
        case Right(_) => fail("expected rejection of a future auth_time")
      }
    }

    "reject a token without an auth_time claim" in {
      service.verifyToken(token(None)).asserting {
        case Left(t)  => t.getMessage should include("auth_time")
        case Right(_) => fail("expected rejection of a missing auth_time")
      }
    }

    "surface the signature failure before looking at auth_time" in {
      val otherKey = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        generator.generateKeyPair().getPrivate match {
          case priv: RSAPrivateKey => priv
          case other               => throw new IllegalStateException(s"expected RSAPrivateKey, got $other")
        }
      }
      val now    = Instant.now()
      val forged = JWT
        .create()
        .withKeyId("kid-1")
        .withIssuer(s"https://securetoken.google.com/$projectId")
        .withAudience(projectId)
        .withSubject("forged")
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(300))
        .withClaim("auth_time", java.lang.Long.valueOf(now.getEpochSecond))
        .sign(Algorithm.RSA256(publicKey, otherKey))
      service.verifyToken(ExternalAuthToken(forged)).asserting(_.isLeft shouldBe true)
    }
  }
}
