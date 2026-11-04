#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8
//> using file workspace
//> using mainClass Doctor

// Smoke-test the dev setup. Diagnose only — does not start anything for you.
// Each step prints pass / fail / skipped, with a one-line hint pointing at
// the fix command on failure. Exit code 0 if all pass, 1 otherwise.
//
//   ./scripts/doctor.scala
//
// Reads `.env` for ports (so changes to PG_PORT etc. are picked up); falls
// back to `.env.sample` defaults when `.env` is missing.

import mainargs.{ParserForMethods, main}

import java.io.File
import java.net.{ConnectException, InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Paths}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import workspace.{AgentsMd, Plan}

object Doctor {

  enum Outcome {
    case Pass(detail: String = "")
    case Fail(detail: String, hint: String)
    case Skip(reason: String)

    def isPass: Boolean = this match { case _: Pass => true; case _ => false }
    def isFail: Boolean = this match { case _: Fail => true; case _ => false }
    def isSkip: Boolean = this match { case _: Skip => true; case _ => false }
  }

  final case class Check(
    name: String,
    outcome: Outcome,
    sub: List[String] = Nil)

  private val isTty: Boolean = System.console() != null
  private val Green: String  = if (isTty) "[32m" else ""
  private val Red: String    = if (isTty) "[31m" else ""
  private val Dim: String    = if (isTty) "[2m" else ""
  private val Reset: String  = if (isTty) "[0m" else ""
  private val passGlyph      = "✓"
  private val failGlyph      = "✗"
  private val skipGlyph      = "-"

  @main(doc = "Check the dev setup: tools, .env, the compose stack, the database and the app. Exit 1 when a check fails.")
  def run(): Unit = {
    val env             = readEnv()
    val pgPort          = env.get("PG_PORT").flatMap(_.toIntOption).getOrElse(55432)
    val appPort         = env.get("PORT").flatMap(_.toIntOption).getOrElse(9000)
    val apiVersion      = "v1" // mirrors madrileno.utils.http.ApiVersion.V1.urlSegment
    val mailpitUiPort   = 58025
    val siloApiPort     = 59000
    val openobservePort = 55080

    val envCheck     = checkEnvFile()
    val dockerCheck  = checkDockerAvailable()
    val composeCheck =
      if (dockerCheck.outcome.isPass) checkComposeServices()
      else Check("docker compose services up", Outcome.Skip("docker not available"))

    val results: List[Check] = List(
      envCheck,
      dockerCheck,
      composeCheck,
      checkPostgresReachable(pgPort),
      checkFullProfileHttp("mailpit reachable", "mailpit", s"http://localhost:$mailpitUiPort/api/v1/info"),
      checkFullProfileHttp("silo reachable", "silo", s"http://localhost:$siloApiPort/minio/health/live"),
      checkFullProfileHttp("openobserve reachable", "openobserve", s"http://localhost:$openobservePort/healthz"),
      checkHttp(
        "app responding",
        s"http://localhost:$appPort/$apiVersion/health-check",
        "./scripts/app.scala start (or ~reStart in an sbt shell to rebuild on save)"
      ),
      checkWorkspace()
    )

    results.foreach(printCheck)

    val passedCount  = results.count(_.outcome.isPass)
    val failedCount  = results.count(_.outcome.isFail)
    val skippedCount = results.count(_.outcome.isSkip)

    println()
    if (failedCount == 0)
      println(s"${Green}All $passedCount checks passed${if (skippedCount > 0) s" ($skippedCount skipped)" else ""}. Happy hacking.${Reset}")
    else
      println(
        s"${Red}$failedCount of ${results.size} checks failed${Reset}${if (skippedCount > 0) s" ($skippedCount skipped)" else ""}. See hints above."
      )

    sys.exit(if (failedCount == 0) 0 else 1)
  }

  // ---- checks ----

  private def checkEnvFile(): Check = {
    val f = new File(".env")
    if (f.isFile) Check(".env present", Outcome.Pass())
    else Check(".env present", Outcome.Fail(detail = "not found", hint = "cp .env.sample .env"))
  }

  private def checkDockerAvailable(): Check =
    runCmd("docker", "--version") match {
      case Right(out) => Check("docker available", Outcome.Pass(out.trim))
      case Left(_)    => Check("docker available", Outcome.Fail(detail = "not on PATH", hint = "install Docker or Docker Desktop"))
    }

  // Compose *service* names (top-level keys in docker-compose.yml), not container names.
  private val RequiredServices: List[String]    = List("postgres")
  private val FullProfileServices: List[String] = List("mailpit", "silo", "openobserve")
  private val FullProfileHint: String           = "docker compose --profile full up -d"

  private lazy val composeStatus: Either[String, Map[String, String]] =
    runCmd("docker", "compose", "ps", "--all", "--format", "{{.Service}}\t{{.Status}}").map { out =>
      out.linesIterator
        .map(_.split('\t').toList)
        .collect { case name :: status :: _ => name -> status }
        .toMap
    }

  private def serviceUp(service: String): Boolean = composeStatus.exists(_.get(service).exists(_.startsWith("Up")))

  private def checkComposeServices(): Check =
    composeStatus match {
      case Left(err) =>
        Check(
          "docker compose services up",
          Outcome.Fail(detail = err.trim.linesIterator.take(1).mkString, hint = "run from project root: docker compose up -d")
        )
      case Right(rows) =>
        def line(svc: String, note: String): String = rows.get(svc) match {
          case Some(status) => f"$svc%-14s $status"
          case None         => f"$svc%-14s NOT RUNNING$note"
        }
        val requiredDown = RequiredServices.filterNot(serviceUp)
        val sub          = RequiredServices.map(line(_, "")) ++ FullProfileServices.map(line(_, " (full profile, optional)"))
        if (requiredDown.isEmpty) Check("docker compose services up", Outcome.Pass(), sub)
        else
          Check(
            "docker compose services up",
            Outcome.Fail(detail = s"${requiredDown.mkString(", ")} not running", hint = "docker compose up -d"),
            sub
          )
    }

  private def checkFullProfileHttp(
    name: String,
    service: String,
    url: String
  ): Check =
    if (serviceUp(service)) checkHttp(name, url, FullProfileHint)
    else Check(name, Outcome.Skip(s"$service not started; part of the full profile: $FullProfileHint"))

  private def checkWorkspace(): Check = {
    val name     = "workspace root up to date"
    val root     = os.pwd / os.up
    val agentsMd = root / "AGENTS.md"
    if (!os.isFile(agentsMd) || !os.read(agentsMd).contains(AgentsMd.Begin))
      Check(name, Outcome.Skip("the parent directory is not a generated workspace"))
    else
      Plan.changes(root, os.pwd.last) match {
        case Right(Nil)     => Check(name, Outcome.Pass(root.toString))
        case Right(changes) =>
          Check(name, Outcome.Fail(detail = s"stale: ${changes.map(_.path).mkString(", ")}", hint = "./scripts/workspace.scala generate"))
        case Left(error) => Check(name, Outcome.Fail(detail = error, hint = "fix that file, then ./scripts/workspace.scala generate"))
      }
  }

  private def checkPostgresReachable(port: Int): Check = {
    val r = tcpReachable("localhost", port, timeoutMs = 1000)
    if (r) Check("postgres reachable", Outcome.Pass(s"localhost:$port"))
    else Check("postgres reachable", Outcome.Fail(detail = s"localhost:$port refused", hint = "docker compose up -d postgres"))
  }

  private def checkHttp(
    name: String,
    url: String,
    hint: String
  ): Check = {
    httpGetStatus(url) match {
      case Right(status) if status >= 200 && status < 400 =>
        Check(name, Outcome.Pass(s"$url → $status"))
      case Right(status) =>
        Check(name, Outcome.Fail(detail = s"$url → $status", hint = hint))
      case Left(err) =>
        Check(name, Outcome.Fail(detail = s"$url: $err", hint = hint))
    }
  }

  // ---- helpers ----

  private def readEnv(): Map[String, String] = {
    val source = if (new File(".env").isFile) ".env" else ".env.sample"
    if (!new File(source).isFile) Map.empty
    else
      Files
        .readAllLines(Paths.get(source))
        .asScala
        .toList
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2) match {
            case Array(k, v) => Some(k.trim -> stripQuotes(v.trim))
            case _           => None
          }
        }
        .toMap
  }

  private def stripQuotes(s: String): String =
    if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) s.substring(1, s.length - 1)
    else s

  private def runCmd(cmd: String*): Either[String, String] = {
    Try {
      val pb     = new ProcessBuilder(cmd*).redirectErrorStream(false)
      val p      = pb.start()
      val stdout = new String(p.getInputStream.readAllBytes())
      val stderr = new String(p.getErrorStream.readAllBytes())
      val rc     = p.waitFor()
      if (rc == 0) Right(stdout) else Left(if (stderr.nonEmpty) stderr else s"exit $rc")
    }.toEither.left.map(_.getMessage).flatMap(identity)
  }

  private def tcpReachable(
    host: String,
    port: Int,
    timeoutMs: Int
  ): Boolean =
    Using(new Socket()) { s =>
      s.connect(new InetSocketAddress(host, port), timeoutMs)
      true
    }.toOption.contains(true)

  private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private def httpGetStatus(url: String): Either[String, Int] =
    Try {
      val req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(3)).build()
      http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }.toEither.left.map {
      case e: ConnectException => "connection refused"
      case e                   => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    }

  private def printCheck(c: Check): Unit = {
    val (glyph, color, suffix) = c.outcome match {
      case Outcome.Pass(d)    => (passGlyph, Green, if (d.nonEmpty) s" ${Dim}($d)${Reset}" else "")
      case Outcome.Fail(d, _) => (failGlyph, Red, s" ${Dim}($d)${Reset}")
      case Outcome.Skip(r)    => (skipGlyph, Dim, s" ${Dim}(skipped: $r)${Reset}")
    }
    println(s"$color$glyph$Reset ${c.name}$suffix")
    c.sub.foreach(line => println(s"    ${Dim}$line${Reset}"))
    c.outcome match {
      case Outcome.Fail(_, hint) => println(s"    ${Dim}Hint: $hint${Reset}")
      case _                     => ()
    }
  }

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runEither(args.toIndexedSeq).left.foreach { usage =>
      System.err.println(usage)
      sys.exit(2)
    }
}
