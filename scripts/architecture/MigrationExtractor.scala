package architecture

import scala.util.matching.Regex

object MigrationExtractor {
  private val Name   = """(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)(?:\.(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*))?"""
  private val Create =
    s"""(?i)\\bcreate\\s+(?:unlogged\\s+)?+table\\s+(?:if\\s+not\\s+exists\\s+)?+((?>$Name))(?!\\s+partition\\s+of\\b)""".r
  private val Alter    = s"""(?i)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?+(?:only\\s+)?+($Name)""".r
  private val Drop     = s"""(?i)\\bdrop\\s+table\\s+(?:if\\s+exists\\s+)?+($Name(?:\\s*,\\s*$Name)*)""".r
  private val Comments = """(?s)--[^\n]*|/\*.*?\*/""".r

  def extract(files: List[SourceFile]): List[Migration] =
    files.map { file =>
      val sql = withoutComments(file.content)
      Migration(file.packagePath, Trees.hash(normalized(sql)), names(Create, sql), names(Alter, sql), names(Drop, sql))
    }

  def created(file: SourceFile): List[MigrationTable] = {
    val sql = withoutComments(file.content)
    Create.findAllMatchIn(sql).map(m => MigrationTable(tableName(m.group(1)), Loc(file.projectPath, sql.take(m.start).count(_ == '\n') + 1))).toList
  }

  def tableName(name: String): String = name.trim.split('.').last.stripPrefix("\"").stripSuffix("\"").toLowerCase

  private def withoutComments(sql: String): String =
    Comments.replaceAllIn(sql, comment => Regex.quoteReplacement(" " + comment.matched.filter(_ == '\n')))

  private def normalized(sql: String): String = sql.replaceAll("\\s+", " ").replaceAll(" ?([(),;]) ?", "$1").trim

  private def names(pattern: Regex, sql: String): List[String] =
    pattern.findAllMatchIn(sql).flatMap(_.group(1).split(',').map(tableName)).toList.distinct
}
