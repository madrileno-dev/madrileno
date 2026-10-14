package madrileno.auth.services

import cats.effect.{Clock, IO}
import io.circe.Decoder
import madrileno.auth.domain.{ExternalAuthToken, Provider, VerifiedExternalToken}

import java.security.interfaces.RSAPublicKey
import java.time.Instant

class FirebaseService(projectId: String, keyResolver: String => IO[RSAPublicKey]) extends ExternalAuthVerifier {
  private val leewaySeconds: Long = 30L
  private val verifier            =
    new Rs256TokenVerifier(
      provider = Provider.Firebase,
      issuer = s"https://securetoken.google.com/$projectId",
      audience = Set(projectId),
      keyResolver = keyResolver
    )

  override def verifyToken(token: ExternalAuthToken): IO[Either[Throwable, VerifiedExternalToken]] =
    verifier.verifyToken(token).flatMap {
      case Left(t)  => IO.pure(Left(t))
      case Right(v) =>
        Clock[IO].realTimeInstant.map(now => validateAuthTime(v, now).map(_ => v))
    }

  private def validateAuthTime(v: VerifiedExternalToken, now: Instant): Either[Throwable, Unit] =
    v.metadata.unwrap.hcursor.downField("auth_time").as[Long](using Decoder.decodeLong) match {
      case Right(authTime) if authTime <= now.getEpochSecond + leewaySeconds =>
        Right(())
      case Right(authTime) =>
        Left(new IllegalArgumentException(s"Firebase ID token: auth_time $authTime is in the future"))
      case Left(_) =>
        Left(new IllegalArgumentException("Firebase ID token: missing or non-numeric 'auth_time' claim"))
    }
}
