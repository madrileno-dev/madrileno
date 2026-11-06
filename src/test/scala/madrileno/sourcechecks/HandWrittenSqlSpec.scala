package madrileno.sourcechecks

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

class HandWrittenSqlSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val exemptions: List[Exemption] = List()

  private def schemaNames: IO[Set[String]] = withSession {
    summon[Session[IO]]
      .execute(sql"""SELECT c.relname::text, a.attname::text
              FROM pg_catalog.pg_attribute a
              JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                AND a.attnum > 0 AND NOT a.attisdropped AND c.relname <> 'flyway_schema_history'""".query(text ~ text))
      .map(_.flatMap { case table ~ column => List(table, column) }.toSet)
  }

  private def found: IO[List[SourceFinding]] =
    for {
      sources <- IO.fromEither(ProjectSources.main.left.map(new IllegalStateException(_)))
      names   <- schemaNames
    } yield sources.flatMap(SqlNameFinder.in(_, names))

  private val advice = List(
    """Name tables and columns through their Table object: ${XTable.n}, ${XTable.column.n} or ${XTable.column.n("alias")}.""",
    "A table without a Table object needs one. A query that has to name them by hand goes in the exemptions of HandWrittenSqlSpec,",
    """as Exemption("<path>", "<member, as printed after in>", "<why it is written by hand>")."""
  )

  "sql strings in src/main" should {
    "read the names of tables and of views, materialized ones included" in {
      val createView = withSession {
        summon[Session[IO]].execute(sql"CREATE MATERIALIZED VIEW probe_totals AS SELECT 1 AS probe_total".command).void
      }
      (createView *> schemaNames).asserting(_ should contain allOf ("scheduled_task", "task_name", "probe_totals", "probe_total"))
    }

    "name tables and columns only through Table objects" in {
      found.asserting { findings =>
        val handWritten = SourceFinding.unexempted(findings, exemptions)
        if (handWritten.isEmpty) succeed else fail(SourceFinding.report("sql strings name tables or columns by hand", handWritten, advice))
      }
    }

    "have no exemption left that covers nothing" in {
      found.asserting(findings => SourceFinding.stale(findings, exemptions) shouldBe empty)
    }
  }
}
