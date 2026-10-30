package architecture

import scala.util.matching.Regex

object MigrationExtractor {
  private val Name     = """(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)(?:\.(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*))?"""
  private val Create   = s"""(?i)\\bcreate\\s+(?:unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?($Name)""".r
  private val Alter    = s"""(?i)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?($Name)""".r
  private val Drop     = s"""(?i)\\bdrop\\s+table\\s+(?:if\\s+exists\\s+)?($Name(?:\\s*,\\s*$Name)*)""".r
  private val Comments = """(?s)--[^\n]*|/\*.*?\*/""".r

  def extract(files: List[SourceFile]): List[Migration] =
    files.map { file =>
      val sql = Comments.replaceAllIn(file.content, " ")
      Migration(file.packagePath, Trees.hash(normalized(sql)), names(Create, sql), names(Alter, sql), names(Drop, sql))
    }

  private def normalized(sql: String): String = sql.replaceAll("\\s+", " ").replaceAll(" ?([(),;]) ?", "$1").trim

  private def names(pattern: Regex, sql: String): List[String] =
    pattern.findAllMatchIn(sql).flatMap(_.group(1).split(',').map(tableName)).toList.distinct

  private def tableName(name: String): String = name.trim.split('.').last.stripPrefix("\"").stripSuffix("\"").toLowerCase
}
