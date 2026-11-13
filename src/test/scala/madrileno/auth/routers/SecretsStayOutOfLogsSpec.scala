package madrileno.auth.routers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import madrileno.auth.domain.FirebaseJwt
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthWithRefreshTokenRequest, AuthenticatedResponse}
import madrileno.support.TestApplicationLoader
import madrileno.utils.json.JsonProtocol.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{AuthScheme, Credentials, EntityEncoder, HttpRoutes, MediaType, Method, Request, Response, Status, Uri}
import org.scalatest.Assertion
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}
import pl.iterators.stir.server.ToHttpRoutes

import scala.jdk.CollectionConverters.*

class SecretsStayOutOfLogsSpec extends AnyFunSpec with Matchers with TestApplicationLoader {

  private lazy val routes: HttpRoutes[IO] = application.routes(WebSocketBuilder2[IO].unsafeRunSync()).toHttpRoutes

  private val externalToken = "test-token"
  private val logLimitBytes = 4096
  private val bogusBearer   = "bogus.bearer.token"

  describe("auth routes") {
    it("keep every credential out of the logs while leaving fingerprints to correlate by") {
      val ((login, refreshed, moved), logged) = capturingLogs {
        val login     = decode[AuthenticatedResponse](post("/v1/auth/firebase", AuthWithFirebaseRequest(FirebaseJwt(externalToken))))
        val refreshed = decode[AuthenticatedResponse](post("/v1/auth/refresh-token", AuthWithRefreshTokenRequest(login.refreshToken)))
        val moved     = decode[AuthenticatedResponse](post("/v1/auth/refresh-token", AuthWithRefreshTokenRequest(refreshed.refreshToken)))
        val replayed  = drain(post("/v1/auth/refresh-token", AuthWithRefreshTokenRequest(login.refreshToken)))
        val rejected  = drain(get("/v1/auth/sessions", bearer = bogusBearer))
        replayed.status shouldBe Status.Unauthorized
        rejected.status shouldBe Status.Unauthorized
        (login, refreshed, moved)
      }

      val secrets =
        List(login, refreshed, moved).flatMap(issued => List(issued.jwt.unwrap, issued.refreshToken.unwrap)) ++ List(externalToken, bogusBearer)
      logged should not be empty
      secrets.foreach(assertAbsent(logged, _))

      logged.exists(line => line.contains("POST /v1/auth/refresh-token") && line.contains("\"refreshToken\":\"REDACTED\"")) shouldBe true
      logged.exists(line => line.contains("\"jwt\":\"REDACTED\"") && line.contains("\"refreshToken\":\"REDACTED\"")) shouldBe true
      logged.exists(line => line.contains("was replayed") && line.contains("(fp:")) shouldBe true
    }

    it("show a JSON body cut at the log limit, still masked, when the cut lands inside a secret") {
      val (login, _) = capturingLogs {
        decode[AuthenticatedResponse](post("/v1/auth/firebase", AuthWithFirebaseRequest(FirebaseJwt(externalToken))))
      }
      val secret  = login.refreshToken.unwrap
      val prefix  = """{"padding":""""
      val between = """","refreshToken":""""
      val padding = "p" * (logLimitBytes - prefix.length - between.length - secret.length / 2)
      val body    = s"""$prefix$padding$between$secret"}"""

      val (status, logged) = capturingLogs {
        drain(postRaw("/v1/auth/refresh-token", body)).status
      }

      status shouldBe Status.Ok
      val requestLine = logged
        .find(line => line.contains("POST /v1/auth/refresh-token") && line.contains("\"padding\":"))
        .getOrElse(fail("no request line was logged"))
      requestLine should include("\"padding\":\"ppp")
      requestLine should include(s" ... (${body.length} bytes total)")
      requestLine should not include secret.take(8)
      requestLine should not include secret.takeRight(8)
      assertAbsent(logged, secret)
    }
  }

  private def assertAbsent(logged: List[String], secret: String): Assertion =
    withClue(s"log lines containing a credential:\n${logged.filter(_.contains(secret)).mkString("\n")}\n") {
      logged.exists(_.contains(secret)) shouldBe false
    }

  private def post[A](path: String, body: A)(using EntityEncoder[IO, A]): Response[IO] =
    routes.orNotFound.run(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)).unsafeRunSync()

  private def postRaw(path: String, json: String): Response[IO] =
    routes.orNotFound
      .run(
        Request[IO](Method.POST, Uri.unsafeFromString(path))
          .withEntity(json)(using EntityEncoder.stringEncoder)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .unsafeRunSync()

  private def get(path: String, bearer: String): Response[IO] =
    routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)).putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, bearer))))
      .unsafeRunSync()

  private def decode[A: Decoder](response: Response[IO]): A = {
    response.status shouldBe Status.Ok
    response.as[A].unsafeRunSync()
  }

  private def drain(response: Response[IO]): Response[IO] = {
    response.bodyText.compile.drain.unsafeRunSync()
    response
  }

  private def capturingLogs[A](body: => A): (A, List[String]) =
    LoggerFactory.getILoggerFactory match {
      case context: LoggerContext =>
        val root     = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val appender = new ListAppender[ILoggingEvent]
        appender.start()
        root.addAppender(appender)
        try {
          val result = body
          (result, appender.synchronized(appender.list.asScala.toList).map(_.getFormattedMessage))
        } finally {
          root.detachAppender(appender)
          appender.stop()
        }
      case other => fail(s"expected logback, found ${other.getClass.getName}")
    }
}
