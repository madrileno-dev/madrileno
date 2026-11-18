package madrileno.support

import com.dimafeng.testcontainers.PostgreSQLContainer
import madrileno.utils.db.transactor.PgConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.util.Try

trait DedicatedPostgres extends TestDatabase with BeforeAndAfterAll { self: Suite =>

  private lazy val dedicatedContainer: Try[PostgreSQLContainer] = Try(TestPostgres.start())

  protected def containerId: String = dedicatedContainer.get.containerId

  override protected lazy val databaseConfig: PgConfig = dedicatedContainer.map(server => TestPostgres.configOf(server, server.databaseName)).get

  override protected def afterAll(): Unit =
    try super.afterAll()
    finally dedicatedContainer.foreach(_.stop())
}
