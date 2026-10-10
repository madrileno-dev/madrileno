package madrileno.main

import cats.effect.{ExitCode, IO, IOApp}
import madrileno.utils.db.Migrations
import madrileno.utils.db.transactor.PgConfig
import org.flywaydb.core.api.output.MigrateResult
import pureconfig.*

object MigrateMain extends IOApp {
  enum Command {
    case Migrate, Info, Validate, Clean
  }

  private def parse(args: List[String]): Either[String, Command] =
    args match {
      case Nil | List("migrate") => Right(Command.Migrate)
      case List("info")          => Right(Command.Info)
      case List("validate")      => Right(Command.Validate)
      case List("clean")         => Right(Command.Clean)
      case other                 =>
        Left(s"unknown command '${other.mkString(" ")}'. Expected: migrate (default), info, validate, or clean")
    }

  override def run(args: List[String]): IO[ExitCode] =
    parse(args) match {
      case Left(error)    => IO.println(s"flyway: $error").as(ExitCode.Error)
      case Right(command) =>
        for {
          pg   <- IO.delay(ConfigSource.default.at("pg").loadOrThrow[PgConfig])
          code <- execute(command, pg)
        } yield code
    }

  private[main] def migrateSummary(result: MigrateResult): String = {
    val version = Option(result.targetSchemaVersion).orElse(Option(result.initialSchemaVersion)).getOrElse("?")
    s"flyway: applied ${result.migrationsExecuted} migration(s); schema now at v$version"
  }

  private def execute(command: Command, pg: PgConfig): IO[ExitCode] =
    command match {
      case Command.Migrate =>
        Migrations.migrate(pg).flatMap { result =>
          IO.println(migrateSummary(result)).as(if (result.success) ExitCode.Success else ExitCode.Error)
        }
      case Command.Info =>
        Migrations.info(pg).flatMap(IO.println(_)).as(ExitCode.Success)
      case Command.Validate =>
        Migrations.validate(pg).flatMap { result =>
          if (result.validationSuccessful) {
            IO.println(s"flyway: validation successful (${result.validateCount} migration(s))").as(ExitCode.Success)
          } else {
            IO.println(s"flyway: validation FAILED — ${result.getAllErrorMessages}").as(ExitCode.Error)
          }
        }
      case Command.Clean =>
        Migrations.clean(pg).flatMap { result =>
          IO.println(s"flyway: clean complete — schemas cleaned ${result.schemasCleaned}, dropped ${result.schemasDropped}")
            .as(ExitCode.Success)
        }
    }
}
