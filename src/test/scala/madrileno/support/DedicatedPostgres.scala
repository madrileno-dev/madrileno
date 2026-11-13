package madrileno.support

import madrileno.utils.db.transactor.PgConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

trait DedicatedPostgres extends TestDatabase with BeforeAndAfterAll { self: Suite =>

  private lazy val dedicatedContainer = TestPostgres.start()

  protected def containerId: String = dedicatedContainer.containerId

  override protected lazy val databaseConfig: PgConfig = TestPostgres.configOf(dedicatedContainer, dedicatedContainer.databaseName)

  override protected def afterAll(): Unit =
    try super.afterAll()
    finally dedicatedContainer.stop()
}
