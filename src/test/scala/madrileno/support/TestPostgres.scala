package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import madrileno.utils.db.transactor.PgConfig
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import scala.util.control.NonFatal
import scala.util.{Try, Using}

object TestPostgres {

  val image: DockerImageName = DockerImageName.parse("postgres:18")

  private val serverOptions       = Seq("postgres", "-c", "max_connections=1000", "-c", "fsync=off")
  private val maintenanceDatabase = "postgres"

  def start(): PostgreSQLContainer = {
    val container = PostgreSQLContainer(image)
    container.container.setCommand(serverOptions*)
    container.start()
    try {
      Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
      container
    } catch {
      case NonFatal(error) =>
        container.stop()
        throw error
    }
  }

  def configOf(container: PostgreSQLContainer, database: String): PgConfig =
    PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = database,
      password = Some(container.password)
    )

  private lazy val shared: Try[PostgreSQLContainer] = Try {
    val started = start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => started.stop()))
    started
  }

  def freshDatabase(): PgConfig = synchronized {
    val server   = shared.get
    val database = s"t_${TestData.randomUuid().toString.replace("-", "")}"
    cloneTemplate(server, database)
    configOf(server, database)
  }

  private def cloneTemplate(server: PostgreSQLContainer, database: String): Unit = {
    val url = s"jdbc:postgresql://${server.host}:${server.mappedPort(5432)}/$maintenanceDatabase"
    Using.resource(DriverManager.getConnection(url, server.username, server.password)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        statement.execute(s"""CREATE DATABASE "$database" TEMPLATE "${server.databaseName}"""")
        ()
      }
    }
  }
}
