package workspace

final case class Member(
  dir: String,
  role: String,
  summary: String,
  body: String) {
  def section(backendDir: String): String = body.replace("{dir}", dir).replace("{backend}", backendDir)
}

object Member {
  val File: os.RelPath = os.rel / ".agents" / "workspace.md"

  private val Fence = "---"
  private val Role  = "[a-z][a-z0-9-]*".r

  def parse(dir: String, text: String): Either[String, Member] = {
    val where = s"$dir/$File"
    text.linesIterator.toList match {
      case Fence :: rest if rest.contains(Fence) =>
        val (front, body) = rest.splitAt(rest.indexOf(Fence))
        val fields        = front.flatMap { line =>
          line.split(":", 2) match {
            case Array(key, value) => Some(key.trim -> value.trim)
            case _                 => None
          }
        }.toMap
        (fields.get("role"), fields.get("summary").filter(_.nonEmpty)) match {
          case (Some(Role()), Some(summary)) => Right(Member(dir, fields("role"), summary, body.drop(1).mkString("\n").trim))
          case (Some(_), Some(_))            => Left(s"$where: role must be one lower-case word, like backend or frontend")
          case (None, _)                     => Left(s"$where: the frontmatter has no role")
          case (_, None)                     => Left(s"$where: the frontmatter has no summary")
        }
      case _ => Left(s"$where: no frontmatter; start the file with ---, role: and summary:, then ---")
    }
  }
}
