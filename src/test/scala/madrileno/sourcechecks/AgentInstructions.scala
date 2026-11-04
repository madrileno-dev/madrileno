package madrileno.sourcechecks

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[sourcechecks] final case class Skill(
  folder: String,
  frontmatter: Map[String, String],
  body: String)

private[sourcechecks] object AgentInstructions {
  val digest: String = SourcesDigest.value

  val agentsFile: Path    = Paths.get("AGENTS.md")
  val skillsRoot: Path    = Paths.get(".agents/skills")
  val claudeSkills: Path  = Paths.get(".claude/skills")
  val workspaceFile: Path = Paths.get(".agents/workspace.md")
  val docsRoot: Path      = Paths.get("docs")

  lazy val skills: List[Skill] =
    Using
      .resource(Files.list(skillsRoot))(_.iterator.asScala.filter(Files.isDirectory(_)).toList.sorted)
      .map { folder =>
        val (frontmatter, body) = split(Files.readString(folder.resolve("SKILL.md")))
        Skill(folder.getFileName.toString, frontmatter, body)
      }

  def split(text: String): (Map[String, String], String) =
    text.linesIterator.toList match {
      case "---" :: rest =>
        val (header, tail) = rest.span(_ != "---")
        val fields         = header.flatMap { line =>
          line.split(":", 2) match {
            case Array(key, value) => Some(key.trim -> value.trim)
            case _                 => None
          }
        }
        (fields.toMap, tail.drop(1).mkString("\n"))
      case lines => (Map.empty, lines.mkString("\n"))
    }

  def docsNamed(text: String): Set[String] =
    """doc `([a-z0-9-]+)`""".r.findAllMatchIn(text).map(_.group(1)).toSet

  def skillsNamed(text: String): Set[String] =
    """`(backend-[a-z0-9-]+)`""".r.findAllMatchIn(text).map(_.group(1)).toSet
}
