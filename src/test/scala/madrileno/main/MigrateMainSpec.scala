package madrileno.main

import org.flywaydb.core.api.output.MigrateResult
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MigrateMainSpec extends AnyWordSpec with Matchers {

  private def result(
    executed: Int,
    initial: Option[String],
    target: Option[String]
  ): MigrateResult = {
    val result = new MigrateResult()
    result.migrationsExecuted = executed
    result.initialSchemaVersion = initial.orNull
    result.targetSchemaVersion = target.orNull
    result
  }

  "MigrateMain.migrateSummary" should {
    "report the version the schema was migrated to" in {
      MigrateMain.migrateSummary(result(2, Some("9"), Some("11"))) shouldBe "flyway: applied 2 migration(s); schema now at v11"
    }

    "report the current version when nothing was migrated" in {
      MigrateMain.migrateSummary(result(0, Some("11"), None)) shouldBe "flyway: applied 0 migration(s); schema now at v11"
    }

    "fall back to a placeholder when the schema has no version at all" in {
      MigrateMain.migrateSummary(result(0, None, None)) shouldBe "flyway: applied 0 migration(s); schema now at v?"
    }
  }
}
