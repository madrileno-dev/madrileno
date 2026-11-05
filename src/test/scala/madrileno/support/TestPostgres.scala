package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

object TestPostgres {

  val image: DockerImageName = DockerImageName.parse("postgres:18")

  def start(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def(dockerImageName = image).start()
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
    container
  }
}
