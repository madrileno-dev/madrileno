package madrileno.utils.db.transactor

import scala.util.matching.Regex

object QuerySummary {
  private val identifier = """"?([A-Za-z_][A-Za-z0-9_]*(?:\."?[A-Za-z_][A-Za-z0-9_]*"?)?)"?"""

  private val statement: Regex  = """(?is)\A\s*(?:with\b.*\)\s*)?(select|insert\s+into|update|delete\s+from)\b(.*)\z""".r
  private val target: Regex     = ("""(?is)\A\s+(?:only\s+)?""" + identifier).r
  private val fromTarget: Regex = ("""(?is)\bfrom\s+""" + identifier).r

  def of(sql: String): Option[String] =
    sql match {
      case statement(verbText, rest) =>
        val verb  = verbText.split("\\s+").head.toUpperCase
        val table = verb match {
          case "SELECT" => fromTarget.findFirstMatchIn(rest).map(_.group(1))
          case _        => target.findFirstMatchIn(rest).map(_.group(1))
        }
        Some(table.fold(verb)(t => s"$verb ${t.replace("\"", "")}"))
      case _ => None
    }
}
