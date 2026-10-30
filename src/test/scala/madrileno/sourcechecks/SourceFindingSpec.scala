package madrileno.sourcechecks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.meta.*

class SourceFindingSpec extends AnyWordSpec with Matchers {
  private def memberOfMarker(source: String): Either[String, String] =
    ProjectSources.parse("R.scala", source).map { parsed =>
      parsed.tree.collect { case marker @ Term.Name("marker") => SourceFinding.at(parsed, marker, 1, "marker").member }.mkString(",")
    }

  "SourceFinding" should {
    "name the member a finding is in by its owner and the outermost def or val, not an inner val" in {
      memberOfMarker("""object SchedulerRepository {
                       |  def reschedule = {
                       |    val command = marker
                       |    command
                       |  }
                       |}""".stripMargin) shouldBe Right("SchedulerRepository.reschedule")
    }

    "name the innermost class or object that has a name, through anonymous classes" in {
      memberOfMarker("""object Outer {
                       |  class Repository {
                       |    def find = new Runnable { def run(): Unit = marker }
                       |  }
                       |}""".stripMargin) shouldBe Right("Repository.find")
    }

    "name a given, a type and a top-level def" in {
      memberOfMarker("""object R {
                       |  given query: String = marker
                       |  type T = marker.type
                       |}
                       |def standalone = marker""".stripMargin) shouldBe Right("R.query,R.T,standalone")
    }

    "name the owner alone for a finding outside any of its members, and skip unnamed vals" in {
      memberOfMarker("""class R(x: Int = marker) {
                       |  val (a, b) = (marker, 1)
                       |}""".stripMargin) shouldBe Right("R,R")
    }
  }
}
