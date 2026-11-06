package madrileno.sourcechecks

import cats.syntax.all.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.util.Using

private[sourcechecks] final case class ParsedSource(path: String, tree: Source)

private[sourcechecks] object ProjectSources {
  val digest: String = SourcesDigest.value

  lazy val main: Either[String, List[ParsedSource]] = parseAll(Paths.get("src/main/scala"))
  lazy val test: Either[String, List[ParsedSource]] = parseAll(Paths.get("src/test/scala"))

  def parse(path: String, text: String): Either[String, ParsedSource] =
    dialects
      .Scala3(Input.VirtualFile(path, text))
      .parse[Source]
      .toEither
      .left
      .map(error => s"$path:${error.pos.startLine + 1} does not parse: ${error.message}")
      .map(ParsedSource(path, _))

  private def parseAll(root: Path): Either[String, List[ParsedSource]] =
    Using
      .resource(Files.walk(root))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toList.sorted)
      .traverse(path => parse(path.toString, Files.readString(path)))
}
