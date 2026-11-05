package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path}

object TestPostgres {

  private val composeImage = """(?m)^  postgres:\n    image: (\S+)$""".r

  lazy val image: DockerImageName =
    composeImage
      .findFirstMatchIn(Files.readString(Path.of("docker-compose.yml")))
      .map(found => DockerImageName.parse(found.group(1)))
      .getOrElse(sys.error("docker-compose.yml has no postgres service image for the tests to run on"))

  def start(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def(dockerImageName = image).start()
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
    container
  }
}
