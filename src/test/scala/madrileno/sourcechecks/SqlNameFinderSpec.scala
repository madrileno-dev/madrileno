package madrileno.sourcechecks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SqlNameFinderSpec extends AnyWordSpec with Matchers {
  private val names = Set("booking", "user", "id", "state", "version", "date")
  private val tq    = "\"\"\""

  private def found(source: String): Either[String, List[(Int, String, String)]] =
    ProjectSources.parse("R.scala", source).map(SqlNameFinder.in(_, names).map(f => (f.line, f.member, f.text)))

  "SqlNameFinder" should {
    "find tables and columns written into sql strings, with their line and enclosing member" in {
      found("""object R {
              |  def find = sql"SELECT id FROM booking b WHERE b.state = $text"
              |}""".stripMargin) shouldBe Right(List((2, "find", "id"), (2, "find", "booking"), (2, "find", "state")))
    }

    "leave names that come from interpolated values alone" in {
      found("""object R {
              |  def find = sql"SELECT ${T.*} FROM ${T.n} b WHERE ${T.id.n("b")} = $uuid"
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "skip quoted strings and comments" in {
      found("object R {\n  def find = sql" + tq + "SELECT 1 -- id\n    /* booking */ WHERE note = 'state'" + tq + "\n}") shouldBe Right(Nil)
    }

    "skip function calls, casts and aliases" in {
      found("""object R {
              |  def find = sql"SELECT version(), $at::date, count(*) AS state"
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "find the table an insert names before its column list" in {
      found("""object R {
              |  def add = sql"INSERT INTO booking(id, state) VALUES ($uuid, $text)"
              |}""".stripMargin) shouldBe Right(List((2, "add", "booking"), (2, "add", "id"), (2, "add", "state")))
    }

    "match names as written, so keywords in capitals are not names" in {
      found("object R {\n  def find = sql" + tq + "SELECT DATE '2026-01-01' FROM \"user\"" + tq + "\n}") shouldBe Right(List((2, "find", "user")))
    }

    "name the val a column list is kept in" in {
      found("""object R {
              |  private val columns = sql"id, state"
              |}""".stripMargin) shouldBe Right(List((2, "columns", "id"), (2, "columns", "state")))
    }

    "count lines inside a multi-line sql string" in {
      found("object R {\n  def find =\n    sql" + tq + "SELECT 1\n      FROM booking" + tq + "\n}") shouldBe Right(List((4, "find", "booking")))
    }

    "ignore other interpolators" in {
      found("""object R {
              |  def message = s"no booking with id $id"
              |}""".stripMargin) shouldBe Right(Nil)
    }
  }
}
