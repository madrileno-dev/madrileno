package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import madrileno.utils.db.transactor.PgConfig
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicReference
import scala.util.Using

object TestPostgres {

  val image: DockerImageName = DockerImageName.parse("postgres:18")

  private final case class SharedServer(container: PostgreSQLContainer, template: String)

  private val maintenanceDatabase = "postgres"
  private val shared              = new AtomicReference[Option[SharedServer]](None)

  private val serverOptions: Seq[String] = Seq("postgres", "-c", "max_connections=1000", "-c", "fsync=off")

  def start(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def(dockerImageName = image).start()
    migrate(container)
    container
  }

  def configOf(container: PostgreSQLContainer, database: String): PgConfig =
    PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = database,
      password = Some(container.password)
    )

  def freshDatabase(): PgConfig = {
    val server = sharedServer()
    val name   = s"t_${TestData.randomUuid().toString.replace("-", "")}"
    server.synchronized {
      createDatabase(server, name)
    }
    configOf(server.container, name)
  }

  private def createDatabase(server: SharedServer, name: String): Unit = {
    val container = server.container
    Using.resource(DriverManager.getConnection(jdbcUrl(container, maintenanceDatabase), container.username, container.password)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        statement.execute(s"""CREATE DATABASE "$name" TEMPLATE "${server.template}"""")
        ()
      }
    }
  }

  def stopShared(): Unit =
    shared.getAndSet(None).foreach(_.container.stop())

  private def jdbcUrl(container: PostgreSQLContainer, database: String): String =
    s"jdbc:postgresql://${container.host}:${container.mappedPort(5432)}/$database"

  private def sharedServer(): SharedServer =
    shared.get.getOrElse {
      this.synchronized {
        shared.get.getOrElse {
          val started = startShared()
          shared.set(Some(started))
          started
        }
      }
    }

  private def startShared(): SharedServer = {
    val container = PostgreSQLContainer(image)
    container.container.setCommand(serverOptions*)
    container.start()
    migrate(container)
    SharedServer(container, container.databaseName)
  }

  private def migrate(container: PostgreSQLContainer): Unit = {
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
    ()
  }
}
