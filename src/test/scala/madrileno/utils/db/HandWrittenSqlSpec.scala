package madrileno.utils.db

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.nio.file.Paths

final case class Exemption(
  file: String,
  member: String,
  reason: String) {
  def covers(name: HandWrittenName): Boolean = name.file == file && name.member == member
}

class HandWrittenSqlSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val exemptions: List[Exemption] = List()

  private val sources = Paths.get("src/main/scala")

  private def schemaNames: IO[Set[String]] = withSession {
    summon[Session[IO]]
      .execute(sql"""SELECT table_name::text, column_name::text FROM information_schema.columns
              WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'""".query(text ~ text))
      .map(_.flatMap { case table ~ column => List(table, column) }.toSet)
  }

  private def found: IO[List[HandWrittenName]] =
    schemaNames.flatMap(names => IO.fromEither(SqlNameFinder.inSources(sources, names).left.map(new IllegalStateException(_))))

  private def report(names: List[HandWrittenName]): String =
    (names.map(n => s"  $sources/${n.file}:${n.line} ${n.name} in ${n.member}") ++ List(
      """Name tables and columns through their Table object: ${XTable.n}, ${XTable.column.n} or ${XTable.column.n("alias")}.""",
      "A table without a Table object needs one. A query that has to name them by hand goes in the exemptions of HandWrittenSqlSpec,",
      """as Exemption("<file under src/main/scala>", "<enclosing def or val>", "<why it is written by hand>")."""
    )).mkString("sql strings name tables or columns by hand:\n", "\n", "")

  "sql strings in src/main" should {
    "name tables and columns only through Table objects" in {
      found.asserting { names =>
        val handWritten = names.filterNot(name => exemptions.exists(_.covers(name)))
        if (handWritten.isEmpty) succeed else fail(report(handWritten))
      }
    }

    "have no exemption left that covers nothing" in {
      found.asserting(names => exemptions.filterNot(exemption => names.exists(exemption.covers)) shouldBe empty)
    }
  }
}
