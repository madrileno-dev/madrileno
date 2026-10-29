package architecture

import scala.meta.*

final case class ParsedFile(
  source: SourceFile,
  pkg: String,
  tree: Source,
  opensRoot: Boolean = false)

object Parsing {
  def parseAll(input: ProjectInput): Extracted[List[ParsedFile]] = {
    val results = input.scalaFiles.map(parse(input.rootPackage, _))
    Extracted(results.collect { case Right(file) => file }, Nil, results.collect { case Left(item) => item })
  }

  def lineOf(tree: Tree): Int = tree.pos.startLine + 1

  private def parse(rootPackage: String, file: SourceFile): Either[Unclassified, ParsedFile] =
    dialects.Scala3(file.content).parse[Source] match {
      case Parsed.Success(tree) =>
        val clauses   = packageOf(tree.stats)
        val declared  = clauses.mkString(".")
        val opensRoot = clauses.indices.drop(1).exists(k => clauses.take(k).mkString(".") == rootPackage)
        Packages
          .relative(rootPackage, declared)
          .map(ParsedFile(file, _, tree, opensRoot))
          .toRight(Unclassified(s"outside-root:${file.packagePath}", s"package $declared is outside $rootPackage", Loc(file.projectPath, 1)))
      case error: Parsed.Error =>
        Left(
          Unclassified(
            s"unparseable:${file.packagePath}",
            s"cannot parse: ${error.message.linesIterator.nextOption().getOrElse("")}",
            Loc(file.projectPath, error.pos.startLine + 1)
          )
        )
    }

  private def packageOf(stats: List[Stat]): List[String] =
    stats.collectFirst { case pkg: Pkg => pkg.ref.syntax :: packageOf(pkg.body.stats) }.getOrElse(Nil)
}
