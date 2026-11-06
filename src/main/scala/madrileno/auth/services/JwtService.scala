package madrileno.auth.services

import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.{JWT, JWTCreator}
import io.circe.*
import madrileno.auth.domain.InternalJwt
import pureconfig.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.{Base64, Collections}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import JwtService.*

class JwtService(config: JwtService.Config) {
  private val algorithm           = Algorithm.HMAC256(config.secret)
  private val leewaySeconds: Long = 30L
  private val verifier            = JWT.require(algorithm).acceptLeeway(leewaySeconds).build()

  def decode[A](jwt: String)(using decoder: Decoder[A]): DecodingResult[A] =
    Try(verifier.verify(jwt)) match {
      case Success(decoded) =>
        parser.parse(new String(Base64.getUrlDecoder.decode(decoded.getPayload), UTF_8)) match {
          case Right(json) =>
            decoder.decodeJson(json) match {
              case Right(value) => DecodingResult.Decoded(value)
              case Left(t)      => DecodingResult.ParsingFailure(t)
            }
          case Left(t) => DecodingResult.ParsingFailure(t)
        }
      case Failure(t: TokenExpiredException) => DecodingResult.Expired(t)
      case Failure(t)                        => DecodingResult.InvalidToken(t)
    }

  def encode[A](
    payload: A,
    now: Instant,
    issuer: Option[String] = None,
    subject: Option[String] = None,
    audience: Option[Set[String]] = None,
    notBefore: Option[Instant] = None,
    jti: Option[String] = None
  )(using encoder: Encoder[A]
  ): InternalJwt = {
    val base = JWT
      .create()
      .withPayload(encoder(payload).asObject.fold(Collections.emptyMap[String, AnyRef]())(jsonObjectToJava))
      .withIssuedAt(now)
      .withExpiresAt(now.plus(config.validFor))
    val finalBuilder = List[JWTCreator.Builder => JWTCreator.Builder](
      b => issuer.fold(b)(b.withIssuer),
      b => subject.fold(b)(b.withSubject),
      b => audience.fold(b)(set => b.withAudience(set.toSeq*)),
      b => notBefore.fold(b)(b.withNotBefore),
      b => jti.fold(b)(b.withJWTId)
    ).foldLeft(base)((b, f) => f(b))
    InternalJwt(finalBuilder.sign(algorithm))
  }

  private def jsonObjectToJava(obj: JsonObject): java.util.Map[String, AnyRef] =
    obj.toMap.flatMap((key, value) => jsonToJava(value).map(key -> _)).asJava

  private def jsonToJava(json: Json): Option[AnyRef] =
    json.fold[Option[AnyRef]](
      jsonNull = None,
      jsonBoolean = b => Some(java.lang.Boolean.valueOf(b)),
      jsonNumber = n => Some(n.toLong.map(java.lang.Long.valueOf(_)).getOrElse(java.lang.Double.valueOf(n.toDouble))),
      jsonString = Some(_),
      jsonArray = arr => Some(arr.flatMap(jsonToJava).asJava),
      jsonObject = obj => Some(jsonObjectToJava(obj))
    )
}

object JwtService {
  final case class Config(secret: String, validFor: Duration) derives ConfigReader
}

enum DecodingResult[A] {
  case Decoded(payload: A)
  case ParsingFailure(t: Throwable)
  case InvalidToken(t: Throwable)
  case Expired(t: Throwable)
}
