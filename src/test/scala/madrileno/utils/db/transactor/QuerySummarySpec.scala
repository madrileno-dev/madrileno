package madrileno.utils.db.transactor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class QuerySummarySpec extends AnyWordSpec with Matchers {

  "QuerySummary.of" should {
    "name a select by its first table" in {
      QuerySummary.of("SELECT id, name FROM task t WHERE t.id = $1") shouldBe Some("SELECT task")
    }

    "name an insert by its target table" in {
      QuerySummary.of("INSERT INTO refresh_token (id, user_id) VALUES ($1, $2) RETURNING *") shouldBe Some("INSERT refresh_token")
    }

    "name an update by its target table" in {
      QuerySummary.of("UPDATE task st1 SET picked = true WHERE id = $1") shouldBe Some("UPDATE task")
    }

    "name a delete by its target table" in {
      QuerySummary.of("DELETE FROM outbox WHERE id = $1") shouldBe Some("DELETE outbox")
    }

    "ignore leading whitespace, newlines and keyword case" in {
      QuerySummary.of("\n   select *\n   from\n   \"user\" u") shouldBe Some("SELECT user")
    }

    "keep a schema-qualified table name" in {
      QuerySummary.of("SELECT 1 FROM public.task") shouldBe Some("SELECT public.task")
    }

    "name a CTE by its final statement" in {
      QuerySummary.of("""WITH locked AS (
          |  UPDATE task SET picked = true WHERE id IN (SELECT id FROM task LIMIT $1) RETURNING *
          |)
          |SELECT * FROM locked ORDER BY priority DESC""".stripMargin) shouldBe Some("SELECT locked")
    }

    "name an insert-select CTE by the insert" in {
      QuerySummary.of("WITH src AS (SELECT 1 FROM task) INSERT INTO outbox (id) SELECT id FROM src") shouldBe Some("INSERT outbox")
    }

    "skip a FROM inside a function call" in {
      QuerySummary.of("SELECT extract(epoch from created_at) FROM task") shouldBe Some("SELECT task")
    }

    "fall back to the verb when a select has no table" in {
      QuerySummary.of("SELECT version()") shouldBe Some("SELECT")
    }

    "fall back to the verb when the first FROM is a subquery" in {
      QuerySummary.of("SELECT count(*) FROM (SELECT 1) x") shouldBe Some("SELECT")
    }

    "leave anything else to skunk" in {
      QuerySummary.of("LISTEN events") shouldBe None
      QuerySummary.of("") shouldBe None
    }
  }
}
