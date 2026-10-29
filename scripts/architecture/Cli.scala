package architecture

import scala.util.Try

enum Output {
  case Html(path: os.Path)
  case Mermaid
  case Json
}

final case class Options(
  project: os.Path,
  diff: Option[String],
  output: Output,
  strict: Boolean)

object Options {
  def defaultHtml(project: os.Path, isDiff: Boolean): os.Path =
    project / "target" / "architecture" / (if (isDiff) "architecture-diff.html" else "architecture.html")
}

final case class Outcome(
  stdout: String,
  written: Option[os.Path],
  strictFailures: List[String])

object Cli {
  private val HeadLabel = "working tree"

  def run(options: Options): Either[String, Outcome] =
    for {
      _     <- Either.cond(os.exists(options.project), (), s"${options.project} does not exist")
      input <- ProjectInput.load(options.project)
      _     <- Either.cond(input.scalaFiles.nonEmpty, (), s"no src/main/scala under ${options.project}; is this a Madrileño project?")
      head = Extractor.extract(input)
      diff    <- diffOf(options, head)
      outcome <- emit(options.output, diff)
    } yield outcome.copy(strictFailures = if (options.strict) strictFailures(diff) else Nil)

  private def diffOf(options: Options, head: ArchModel): Either[String, DiffModel] =
    options.diff match {
      case None      => Right(ArchDiff.full(head, HeadLabel))
      case Some(ref) =>
        GitRevision
          .withRevision(options.project, ref) { revision =>
            ProjectInput.load(revision.root).map(base => ArchDiff.compare(Extractor.extract(base), head, baseLabel(ref, revision.sha), HeadLabel))
          }
          .flatten
    }

  def baseLabel(ref: String, sha: String): String =
    if (sha.startsWith(ref)) ref else s"$ref@${sha.take(7)}"

  private def emit(output: Output, diff: DiffModel): Either[String, Outcome] = output match {
    case Output.Html(path) =>
      Try(os.write.over(path, Html.render(diff), createFolders = true)).toEither.left
        .map(error => s"cannot write $path: ${error.getMessage}")
        .map(_ => Outcome("", Some(path), Nil))
    case Output.Mermaid => Right(Outcome(if (diff.isDiff) Mermaid.render(diff) else Mermaid.renderFull(diff), None, Nil))
    case Output.Json    => Right(Outcome(upickle.default.write(diff, indent = 2), None, Nil))
  }

  private def strictFailures(diff: DiffModel): List[String] = {
    val counted = (status: WarningStatus) => if (diff.isDiff) status == WarningStatus.New else status != WarningStatus.Resolved
    diff.warnings.filter(w => counted(w.status)).map(w => s"${w.warning.loc.path}:${w.warning.loc.line} ${w.warning.message}") ++
      diff.unclassified.filter(u => counted(u.status)).map(u => s"${u.item.loc.path}:${u.item.loc.line} ${u.item.description}")
  }
}
