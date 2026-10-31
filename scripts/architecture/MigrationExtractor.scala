package architecture

import scala.util.matching.Regex

object MigrationExtractor {
  private val Name   = """(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)(?:\.(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*))?"""
  private val Create =
    s"""(?i)\\bcreate\\s+(?:unlogged\\s+)?+table\\s+(?:if\\s+not\\s+exists\\s+)?+((?>$Name))(?!\\s+partition\\s+of\\b)""".r
  private val Alter    = s"""(?i)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?+(?:only\\s+)?+($Name)""".r
  private val Drop     = s"""(?i)\\bdrop\\s+table\\s+(?:if\\s+exists\\s+)?+($Name(?:\\s*,\\s*$Name)*)""".r
  private val Rename   = s"""(?i)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?+(?:only\\s+)?+($Name)\\s+rename\\s+to\\s+($Name)""".r
  private val Version  = """V(\d+(?:[._]\d+)*)__.*""".r
  private val Comments = """(?s)((?<![A-Za-z0-9_])[Ee]'(?:[^'\\]|\\.|'')*'|'(?:[^']|'')*')|--[^\n]*|/\*.*?\*/""".r
  private val Part     = """"([^"]+)"|([A-Za-z0-9_$]+)""".r

  def extract(files: List[SourceFile]): List[Migration] =
    files.map { file =>
      val sql = withoutLiterals(file.content)
      Migration(
        file.packagePath,
        Trees.hash(normalized(withoutComments(file.content))),
        names(Create, sql),
        (names(Alter, sql) ++ Rename.findAllMatchIn(sql).map(m => tableName(m.group(2)))).distinct,
        names(Drop, sql)
      )
    }

  def created(file: SourceFile): List[MigrationTable] = {
    val sql = withoutLiterals(file.content)
    Create.findAllMatchIn(sql).map(m => MigrationTable(tableName(m.group(1)), lineOf(file, sql, m.start))).toList
  }

  private enum Statement {
    case Created(table: MigrationTable)
    case Dropped(names: List[String])
    case Renamed(from: String, to: MigrationTable)
  }

  def existing(files: List[SourceFile]): List[MigrationTable] =
    inVersionOrder(files).flatMap(statementsIn).foldLeft(List.empty[MigrationTable]) {
      case (tables, Statement.Created(table))    => tables :+ table
      case (tables, Statement.Dropped(names))    => tables.filterNot(table => names.contains(table.name))
      case (tables, Statement.Renamed(from, to)) => tables.map(table => if (table.name == from) to else table)
    }

  private def statementsIn(file: SourceFile): List[Statement] = {
    val sql = withoutLiterals(file.content)
    (Create.findAllMatchIn(sql).map(m => m.start -> Statement.Created(MigrationTable(tableName(m.group(1)), lineOf(file, sql, m.start)))) ++
      Drop.findAllMatchIn(sql).map(m => m.start -> Statement.Dropped(m.group(1).split(',').map(tableName).toList)) ++
      Rename
        .findAllMatchIn(sql)
        .map(m => m.start -> Statement.Renamed(tableName(m.group(1)), MigrationTable(tableName(m.group(2)), lineOf(file, sql, m.start))))).toList
      .sortBy(_._1)
      .map(_._2)
  }

  private def lineOf(
    file: SourceFile,
    sql: String,
    offset: Int
  ): Loc = Loc(file.projectPath, sql.take(offset).count(_ == '\n') + 1)

  private def inVersionOrder(files: List[SourceFile]): List[SourceFile] = {
    val versioned = files.flatMap(file =>
      file.packagePath.split('/').last match {
        case Version(version) => Some(version.split("[._]").map(BigInt(_)).toList -> file)
        case _                => None
      }
    )
    val ordered = versioned.sortWith { case ((a, _), (b, _)) =>
      a.zipAll(b, BigInt(-1), BigInt(-1)).find((x, y) => x != y).exists((x, y) => x < y)
    }
    ordered.map(_._2) ++ files.filterNot(file => versioned.exists(_._2 eq file))
  }

  def tableName(name: String): String =
    Part.findAllMatchIn(name).toList.lastOption.map(part => Option(part.group(1)).getOrElse(part.group(2))).getOrElse(name.trim).toLowerCase

  private def withoutComments(sql: String): String =
    Comments.replaceAllIn(sql, found => Regex.quoteReplacement(if (found.group(1) != null) found.matched else blank(found.matched)))

  private def withoutLiterals(sql: String): String =
    Comments.replaceAllIn(sql, found => Regex.quoteReplacement(if (found.group(1) != null) s"''${blank(found.matched)}" else blank(found.matched)))

  private def blank(text: String): String = " " + text.filter(_ == '\n')

  private def normalized(sql: String): String = sql.replaceAll("\\s+", " ").replaceAll(" ?([(),;]) ?", "$1").trim

  private def names(pattern: Regex, sql: String): List[String] =
    pattern.findAllMatchIn(sql).flatMap(_.group(1).split(',').map(tableName)).toList.distinct
}
