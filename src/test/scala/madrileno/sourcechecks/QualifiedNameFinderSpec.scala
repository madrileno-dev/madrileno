package madrileno.sourcechecks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class QualifiedNameFinderSpec extends AnyWordSpec with Matchers {
  private val roots = Set("java", "scala", "app", "io", "org", "sttp", "fs2")

  private def found(source: String): Either[String, List[(Int, String, String)]] =
    ProjectSources.parse("R.scala", source).map(QualifiedNameFinder.in(_, roots).map(f => (f.line, f.member, f.text)))

  "QualifiedNameFinder" should {
    "find qualified types and terms, with their line, enclosing member and what to import" in {
      found("""package app
              |object R {
              |  def f(d: scala.concurrent.duration.FiniteDuration) = java.util.UUID.fromString(d.toString)
              |}""".stripMargin) shouldBe Right(List((3, "R.f", "scala.concurrent.duration.FiniteDuration"), (3, "R.f", "java.util.UUID")))
    }

    "leave imports and the package clause alone" in {
      found("""package app.inner
              |import java.util.UUID
              |object R {
              |  def f = {
              |    import scala.concurrent.duration.FiniteDuration
              |    UUID.fromString("1-2-3-4-5")
              |  }
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "allow a name that is already taken: by Scala, by the file's own definitions, or by an import from elsewhere" in {
      found("""package app
              |import org.http4s.Header
              |object R {
              |  type Encoder[A] = io.circe.Encoder[A]
              |  def f(b: Boolean) = (java.lang.Boolean.valueOf(b), java.util.Map.of(), sttp.model.Header("a", "b"), io.circe.Encoder)
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "find a qualified name the file already imports from the same place" in {
      found("""package app
              |import app.task.Task
              |object R {
              |  def t: app.task.Task[Int] = ???
              |}""".stripMargin) shouldBe Right(List((4, "R.t", "app.task.Task")))
    }

    "leave names qualified by an imported package, and package paths that reach no class, alone" in {
      found("""package app
              |import scala.collection.mutable
              |object R {
              |  val buffer = mutable.ListBuffer.empty[Int]
              |  val decode = fs2.text.utf8.decode
              |  val most   = scala.math.max(1, 2)
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "leave selections on values alone" in {
      found("""package app
              |object R {
              |  def f(user: User) = (user.name.value, Codecs.json.encoder)
              |}""".stripMargin) shouldBe Right(Nil)
    }

    "take root packages from package clauses and top-level imports, not from local or relative imports" in {
      val source = ProjectSources.parse(
        "R.scala",
        """package app.inner
          |import cats.syntax.all.*
          |import Codecs.*
          |object R {
          |  def f = {
          |    import config.*
          |    1
          |  }
          |}""".stripMargin
      )
      source.map(s => QualifiedNameFinder.roots(List(s))) shouldBe Right(Set("java", "javax", "scala", "app", "cats"))
    }
  }
}
