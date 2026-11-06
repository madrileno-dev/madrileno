package madrileno.sourcechecks

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProjectSourcesSpec extends AnyWordSpec with Matchers with EitherValues {
  "ProjectSources" should {
    "report a file that does not parse, with the line" in {
      ProjectSources.parse("R.scala", "object R {\n  def f = (1,\n}").left.map(_.startsWith("R.scala:3 does not parse")) shouldBe Left(true)
    }

    "read the main and test sources with paths from the project root" in {
      val main = ProjectSources.main.value.map(_.path)
      val test = ProjectSources.test.value.map(_.path)
      main.filter(_.endsWith("/main/ApplicationLoader.scala")) should have size 1
      test.filter(_.endsWith("/sourcechecks/ProjectSourcesSpec.scala")) should have size 1
      all(main) should startWith("src/main/scala/")
      all(test) should startWith("src/test/scala/")
    }
  }
}
