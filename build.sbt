name := "madrileno"
organization := "pl.iterators"
version := "1.0.0-SNAPSHOT"

scalaVersion := "3.9.0"

libraryDependencies ++= {
  val http4sV            = "0.23.36"
  val http4sStirV        = "0.5.0"
  val http4sOtelV        = "0.18.0"
  val circeV             = "0.14.16"
  val pureconfigV        = "0.17.10"
  val sttpV              = "4.0.26"
  val skunkV             = "2.0.0-RC2"
  val postgresqlV        = "42.7.13"
  val catsV              = "2.13.0"
  val catsEffectV        = "3.7.1"
  val catsEffectTestingV = "1.8.0"
  val chimneyV           = "1.11.0"
  val kebsV              = "2.2.1"
  val logbackV           = "1.6.3"
  val log4catsV          = "2.8.0"
  val otel4sV            = "1.1.0"
  val otelV              = "1.65.0"
  val otelLogbackV       = "2.31.1-alpha"
  val macwireV           = "2.6.7"
  val sealedV            = "2.0.1"
  val javaJwtV           = "4.6.0"
  val cron4sV            = "0.8.2"
  val catsRetryV         = "4.0.0"
  val circuitV           = "0.5.1"
  val jakartaMailV       = "2.1.5"
  val angusMailV         = "2.0.5"
  val scalatagsV         = "0.13.1"
  val scaffeineV         = "5.3.0"
  val testcontainersV    = "0.44.1"
  val baklavaV           = "2.1.0"
  val swaggerUiV         = "5.32.14"
  val flywayV            = "13.5.0"
  val awsSdkV            = "2.54.13"
  val scrimageV          = "4.6.7"
  val scalatestV         = "3.2.20"
  val reflectionsV       = "0.10.2"
  Seq(
    "org.http4s"                      %% "http4s-ember-server"                       % http4sV,
    "org.http4s"                      %% "http4s-dsl"                                % http4sV,
    "org.http4s"                      %% "http4s-circe"                              % http4sV,
    "pl.iterators"                    %% "http4s-stir"                               % http4sStirV,
    "org.http4s"                      %% "http4s-otel4s-middleware-core"             % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-metrics"          % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-trace-core"       % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-trace-server"     % http4sOtelV,
    "io.circe"                        %% "circe-core"                                % circeV,
    "ch.qos.logback"                   % "logback-classic"                           % logbackV,
    "org.typelevel"                   %% "log4cats-slf4j"                            % log4catsV,
    "org.typelevel"                   %% "otel4s-oteljava"                           % otel4sV,
    "io.opentelemetry"                 % "opentelemetry-exporter-otlp"               % otelV              % Runtime,
    "io.opentelemetry"                 % "opentelemetry-sdk-extension-autoconfigure" % otelV              % Runtime,
    "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"        % otelLogbackV,
    "com.softwaremill.macwire"        %% "macros"                                    % macwireV           % "provided",
    "com.softwaremill.macwire"        %% "proxy"                                     % macwireV,
    "com.softwaremill.macwire"        %% "util"                                      % macwireV,
    "com.github.pureconfig"           %% "pureconfig-core"                           % pureconfigV,
    "com.github.pureconfig"           %% "pureconfig-ip4s"                           % pureconfigV,
    "com.github.pureconfig"           %% "pureconfig-generic-scala3"                 % pureconfigV,
    "com.softwaremill.sttp.client4"   %% "fs2"                                       % sttpV,
    "com.softwaremill.sttp.client4"   %% "circe"                                     % sttpV,
    "com.softwaremill.sttp.client4"   %% "opentelemetry-backend"                     % sttpV,
    "com.softwaremill.sttp.client4"   %% "cats"                                      % sttpV              % "test",
    "org.tpolecat"                    %% "skunk-core"                                % skunkV,
    "org.tpolecat"                    %% "skunk-circe"                               % skunkV,
    "org.postgresql"                   % "postgresql"                                % postgresqlV,
    "io.scalaland"                    %% "chimney"                                   % chimneyV,
    "org.typelevel"                   %% "cats-core"                                 % catsV,
    "org.typelevel"                   %% "cats-effect"                               % catsEffectV,
    "pl.iterators"                    %% "kebs-http4s-stir"                          % kebsV,
    "pl.iterators"                    %% "kebs-circe"                                % kebsV,
    "pl.iterators"                    %% "kebs-opaque"                               % kebsV,
    "pl.iterators"                    %% "kebs-instances"                            % kebsV,
    "pl.iterators"                    %% "sealed-monad"                              % sealedV,
    "com.auth0"                        % "java-jwt"                                  % javaJwtV,
    "com.github.alonsodomin.cron4s"   %% "cron4s-core"                               % cron4sV,
    "com.github.cb372"                %% "cats-retry"                                % catsRetryV,
    "io.chrisdavenport"               %% "circuit"                                   % circuitV,
    "jakarta.mail"                     % "jakarta.mail-api"                          % jakartaMailV,
    "org.eclipse.angus"                % "angus-mail"                                % angusMailV         % Runtime,
    "com.lihaoyi"                     %% "scalatags"                                 % scalatagsV,
    "com.github.blemale"              %% "scaffeine"                                 % scaffeineV,
    "pl.iterators"                    %% "baklava-http4s-routes"                     % baklavaV,
    "org.webjars"                      % "swagger-ui"                                % swaggerUiV,
    "software.amazon.awssdk"           % "s3"                                        % awsSdkV,
    "com.sksamuel.scrimage"            % "scrimage-core"                             % scrimageV,
    "pl.iterators"                    %% "http4s-stir-testkit"                       % http4sStirV        % "test",
    "pl.iterators"                    %% "baklava-http4s"                            % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-scalatest"                         % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-openapi"                           % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-simple"                            % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-orpc"                              % baklavaV           % "test",
    "pl.iterators"                    %% "kebs-baklava"                              % kebsV              % "test",
    "com.dimafeng"                    %% "testcontainers-scala-scalatest"            % testcontainersV    % "test",
    "com.dimafeng"                    %% "testcontainers-scala-postgresql"           % testcontainersV    % "test",
    "org.flywaydb"                     % "flyway-core"                               % flywayV,
    "org.flywaydb"                     % "flyway-database-postgresql"                % flywayV,
    "org.scalatest"                   %% "scalatest"                                 % scalatestV         % "test",
    "org.reflections"                  % "reflections"                               % reflectionsV       % "test",
    "org.typelevel"                   %% "cats-effect-testkit"                       % catsEffectV        % "test",
    "org.typelevel"                   %% "cats-effect-testing-scalatest"             % catsEffectTestingV % "test"
  )
}
Test / scalacOptions ++= Seq("-Wconf:msg=should not be used as infix:s", "-Wconf:msg=unused value of type org.scalatest:s")

javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"

Compile / run / fork := true

Compile / mainClass := Some("madrileno.main.Main")

// native-packager
enablePlugins(JavaServerAppPackaging, BaklavaSbtPlugin)
import com.typesafe.sbt.packager.docker.Cmd
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository := sys.env.get("DOCKER_REPO")
packageName := "madrileno"
dockerBaseImage := "azul/zulu-openjdk:21"
Docker / daemonUser := "noroot"
dockerUpdateLatest := true

Compile / doc / sources := Seq.empty

// baklava
inConfig(Test)(
  BaklavaSbtPlugin.settings(Test) ++ Seq(
    fork := false,
    baklavaGenerateConfigs := Map(
      "openapi-info" ->
        s"""
          |{
          |  "openapi" : "3.0.1",
          |  "info" : {
          |    "title" : "${name.value}"
          |  },
          |  "servers" : [
          |    {
          |      "url" : "${sys.env.getOrElse("BASE_URL", "http://localhost:9000")}",
          |      "description" : "Resolved from BASE_URL at generation time; override per deployment"
          |    }
          |  ]
          |}
          |""".stripMargin,
      "orpc-package-contract-json" ->
        s"""
          |{
          |  "name": "@madrileno-dev/${name.value}-orpc-contracts",
          |  "version": "${version.value}",
          |  "main": "index.js",
          |  "types": "index.d.ts"
          |}
          |""".stripMargin
    )
  )
)

// Cache runtime classpath for `./scripts/dev-console.scala`.
Compile / compile := Def.uncached {
  val r           = (Compile / compile).value
  val runtimeJars = update.value.configurations
    .find(_.configuration.name == "runtime")
    .toSeq
    .flatMap(_.modules.flatMap(_.artifacts.map(_._2.getAbsolutePath)))
  val classDir    = (Compile / classDirectory).value.getAbsolutePath
  val resourceDir = (Compile / resourceDirectory).value.getAbsolutePath
  val cp          = (classDir +: resourceDir +: runtimeJars).mkString(":")
  val dest        = baseDirectory.value / "target" / "console-classpath"
  IO.write(dest, cp)
  streams.value.log.info(s"dev-console classpath cached at $dest")
  r
}

// scalafix
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

lazy val verifyAll = taskKey[Unit]("Performs all verifications to assure that the build will pass CI checks.")
Test / verifyAll := Def.uncached {
  Def.sequential(Compile / scalafmtSbtCheck, Compile / scalafmtCheckAll, Compile / compile, Test / testFull).value
}
