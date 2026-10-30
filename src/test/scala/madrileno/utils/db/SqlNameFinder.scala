package madrileno.utils.db

import cats.syntax.all.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.util.Using
import scala.util.matching.Regex

final case class HandWrittenName(
  file: String,
  line: Int,
  member: String,
  name: String)

object SqlNameFinder {
  private enum Token {
    case Word(text: String, offset: Int)
    case Symbol(text: String)
  }

  private val Hole = "\u0000"

  private val Lexeme: Regex = """(?s)'(?:[^']|'')*'|--[^\n]*|/\*.*?\*/|"(?:[^"]|"")*"|[A-Za-z_][A-Za-z0-9_$]*|::|\S""".r

  def inSources(root: Path, names: Set[String]): Either[String, List[HandWrittenName]] =
    Using
      .resource(Files.walk(root))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted)
      .traverse(path => in(root.relativize(path).toString, Files.readString(path), names))
      .map(_.flatten)

  def in(
    file: String,
    source: String,
    names: Set[String]
  ): Either[String, List[HandWrittenName]] =
    dialects
      .Scala3(Input.VirtualFile(file, source))
      .parse[Source]
      .toEither
      .left
      .map(error => s"$file does not parse: ${error.message}")
      .map(_.collect { case sql: Term.Interpolate if sql.prefix.value == "sql" => written(file, sql, names) }.flatten)

  private def written(
    file: String,
    sql: Term.Interpolate,
    names: Set[String]
  ): List[HandWrittenName] = {
    val parts  = sql.parts.collect { case part: Lit.String => part }
    val starts = parts.scanLeft(0)((offset, part) => offset + part.value.length + Hole.length).init
    val tokens = Lexeme.findAllMatchIn(parts.map(_.value).mkString(Hole)).flatMap(token).toVector
    tokens.indices.toList.flatMap { i =>
      tokens(i) match {
        case Token.Word(text, offset) if names.contains(text) && !skipped(tokens.lift(i - 1), tokens.lift(i + 1)) =>
          val part = starts.lastIndexWhere(_ <= offset)
          val line = parts(part).pos.startLine + parts(part).value.take(offset - starts(part)).count(_ == '\n') + 1
          List(HandWrittenName(file, line, memberOf(sql), text))
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

  private def memberOf(tree: Tree): String = tree.parent match {
    case Some(defn: Defn.Def)    => defn.name.value
    case Some(defn: Defn.Val)    => defn.pats.collectFirst { case Pat.Var(name) => name.value }.getOrElse("")
    case Some(defn: Defn.Object) => defn.name.value
    case Some(defn: Defn.Class)  => defn.name.value
    case Some(other)             => memberOf(other)
    case None                    => ""
  }
}
