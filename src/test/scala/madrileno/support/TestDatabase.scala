package madrileno.support

import madrileno.utils.db.transactor.PgConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

trait TestDatabase extends BeforeAndAfterAll { self: Suite =>

  protected lazy val databaseConfig: PgConfig = TestPostgres.freshDatabase()

  override protected def beforeAll(): Unit = {
    val _ = databaseConfig
    super.beforeAll()
  }
}
