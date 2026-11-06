package madrileno.sourcechecks

import scala.meta.*

private[sourcechecks] final case class SourceFinding(
  path: String,
  line: Int,
  member: String,
  text: String)

private[sourcechecks] final case class Exemption(
  path: String,
  member: String,
  reason: String) {
  def covers(finding: SourceFinding): Boolean = finding.path == path && finding.member == member
}

private[sourcechecks] object SourceFinding {
  def at(
    source: ParsedSource,
    tree: Tree,
    line: Int,
    text: String
  ): SourceFinding =
    SourceFinding(source.path, line, memberOf(tree), text)

  def unexempted(findings: List[SourceFinding], exemptions: List[Exemption]): List[SourceFinding] =
    findings.filterNot(finding => exemptions.exists(_.covers(finding)))

  def stale(findings: List[SourceFinding], exemptions: List[Exemption]): List[Exemption] =
    exemptions.filterNot(exemption => findings.exists(exemption.covers))

  def report(
    heading: String,
    findings: List[SourceFinding],
    advice: List[String]
  ): String =
    (findings.map(f => s"  ${f.path}:${f.line} ${f.text} in ${f.member}") ++ advice).mkString(s"$heading:\n", "\n", "")

  private def memberOf(tree: Tree): String = {
    val (inside, outside) = Trees.ancestors(tree).span(ownerName(_).isEmpty)
    (outside.headOption.flatMap(ownerName).toList ++ inside.flatMap(memberName).lastOption.toList).mkString(".")
  }

  private def ownerName(tree: Tree): Option[String] = (tree match {
    case defn: Defn.Object => Some(defn.name.value)
    case defn: Defn.Class  => Some(defn.name.value)
    case defn: Defn.Trait  => Some(defn.name.value)
    case defn: Defn.Enum   => Some(defn.name.value)
    case _                 => None
  }).filter(_.nonEmpty)

  private def memberName(tree: Tree): Option[String] = (tree match {
    case defn: Defn.Def        => Some(defn.name.value)
    case defn: Defn.Val        => defn.pats.collectFirst { case Pat.Var(name) => name.value }
    case defn: Defn.Type       => Some(defn.name.value)
    case defn: Defn.GivenAlias => Some(defn.name.value)
    case defn: Defn.Given      => Some(defn.name.value)
    case _                     => None
  }).filter(_.nonEmpty)
}

private[sourcechecks] object Trees {
  def ancestors(tree: Tree): List[Tree] = tree.parent.toList.flatMap(parent => parent :: ancestors(parent))
}
