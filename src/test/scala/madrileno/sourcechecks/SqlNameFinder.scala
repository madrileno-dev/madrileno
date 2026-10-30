package madrileno.sourcechecks

import scala.meta.*
import scala.util.matching.Regex

object SqlNameFinder {
  private enum Token {
    case Word(text: String, offset: Int)
    case Symbol(text: String)
  }

  private val Hole = "\u0000"

  private val Lexeme: Regex = """(?s)'(?:[^']|'')*'|--[^\n]*|/\*.*?\*/|"(?:[^"]|"")*"|[A-Za-z_][A-Za-z0-9_$]*|::|\S""".r

  def in(source: ParsedSource, names: Set[String]): List[SourceFinding] =
    source.tree.collect { case sql: Term.Interpolate if sql.prefix.value == "sql" => written(source, sql, names) }.flatten

  private def written(
    source: ParsedSource,
    sql: Term.Interpolate,
    names: Set[String]
  ): List[SourceFinding] = {
    val parts  = sql.parts.collect { case part: Lit.String => part }
    val starts = parts.scanLeft(0)((offset, part) => offset + part.value.length + Hole.length).init
    val tokens = Lexeme.findAllMatchIn(parts.map(_.value).mkString(Hole)).flatMap(token).toVector
    tokens.indices.toList.flatMap { i =>
      tokens(i) match {
        case Token.Word(text, offset) if names.contains(text) && !skipped(tokens.lift(i - 1), tokens.lift(i + 1)) =>
          val part = starts.lastIndexWhere(_ <= offset)
          val line = parts(part).pos.startLine + parts(part).value.take(offset - starts(part)).count(_ == '\n') + 1
          List(SourceFinding.at(source, sql, line, text))
        case _ => Nil
      }
    }
  }

  private def token(lexeme: Regex.Match): Option[Token] = {
    val text = lexeme.matched
    if (text.startsWith("'") || text.startsWith("--") || text.startsWith("/*")) None
    else if (text.startsWith("\"")) Some(Token.Word(text.drop(1).dropRight(1).replace("\"\"", "\""), lexeme.start))
    else if (text.head.isLetter || text.head == '_') Some(Token.Word(text, lexeme.start))
    else Some(Token.Symbol(text))
  }

  private def skipped(before: Option[Token], after: Option[Token]): Boolean = {
    def keyword(word: String) = before.exists {
      case Token.Word(text, _) => text.equalsIgnoreCase(word)
      case _                   => false
    }
    before.contains(Token.Symbol("::")) || keyword("AS") || (after.contains(Token.Symbol("(")) && !keyword("INTO"))
  }
}
