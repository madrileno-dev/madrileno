package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import madrileno.utils.db.transactor.PgConfig
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import scala.util.Using

object TestPostgres {

  val image: DockerImageName = DockerImageName.parse("postgres:18")

  private val serverOptions       = Seq("postgres", "-c", "max_connections=1000", "-c", "fsync=off")
  private val maintenanceDatabase = "postgres"

  def start(): PostgreSQLContainer = {
    val container = PostgreSQLContainer(image)
    container.container.setCommand(serverOptions*)
    container.start()
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
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

  private lazy val shared: PostgreSQLContainer = {
    val started = start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => started.stop()))
    started
  }

  def freshDatabase(): PgConfig = synchronized {
    val database = s"t_${TestData.randomUuid().toString.replace("-", "")}"
    cloneTemplate(shared, database)
    configOf(shared, database)
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
