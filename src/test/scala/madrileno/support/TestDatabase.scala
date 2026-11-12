package madrileno.support

import madrileno.utils.db.transactor.PgConfig

trait TestDatabase {
  protected lazy val databaseConfig: PgConfig = TestPostgres.freshDatabase()
}
