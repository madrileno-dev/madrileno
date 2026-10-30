package madrileno.sourcechecks

import cats.syntax.all.*
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class QualifiedNamesSpec extends AnyWordSpec with Matchers with EitherValues {

  private val exemptions: List[Exemption] = List()

  private lazy val found: List[SourceFinding] = {
    val sources = (ProjectSources.main, ProjectSources.test).mapN(_ ++ _).value
    val roots   = QualifiedNameFinder.roots(sources)
    sources.flatMap(QualifiedNameFinder.in(_, roots))
  }

  private val advice = List(
    "Import the name at the top of the file (import java.util.UUID) and write UUID, or import its package and qualify by that",
    "(import scala.collection.mutable, then mutable.ListBuffer). A name that clashes with one already taken can be renamed on import",
    """(import java.util.Map as JMap). A name that has to stay qualified goes in the exemptions of QualifiedNamesSpec, as""",
    """Exemption("<path>", "<enclosing def or val>", "<why it stays qualified>")."""
  )

  "Scala sources" should {
    "import names instead of writing them out from the root package" in {
      val qualified = SourceFinding.unexempted(found, exemptions)
      if (qualified.isEmpty) succeed else fail(SourceFinding.report("names written out from the root package", qualified, advice))
    }

    "have no exemption left that covers nothing" in {
      SourceFinding.stale(found, exemptions) shouldBe empty
    }
  }
}
