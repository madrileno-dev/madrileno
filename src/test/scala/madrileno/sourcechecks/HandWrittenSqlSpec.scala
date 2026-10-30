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
      .execute(sql"""SELECT table_name::text, column_name::text FROM information_schema.columns
              WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'""".query(text ~ text))
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
    """as Exemption("<path>", "<enclosing def or val>", "<why it is written by hand>")."""
  )

  "sql strings in src/main" should {
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
