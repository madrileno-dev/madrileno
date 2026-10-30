package madrileno.sourcechecks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProjectSourcesSpec extends AnyWordSpec with Matchers {
  "ProjectSources" should {
    "report a file that does not parse, with the line" in {
      ProjectSources.parse("R.scala", "object R {\n  def f = (1,\n}").left.map(_.startsWith("R.scala:3 does not parse")) shouldBe Left(true)
    }

    "read the main and test sources with paths from the project root" in {
      val paths = ProjectSources.main.map(_.map(_.path)).getOrElse(Nil) ++ ProjectSources.test.map(_.map(_.path)).getOrElse(Nil)
      paths should contain allOf (
        "src/main/scala/madrileno/main/ApplicationLoader.scala",
        "src/test/scala/madrileno/sourcechecks/ProjectSourcesSpec.scala"
      )
    }
  }
}
