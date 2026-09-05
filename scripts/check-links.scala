#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default

// Walks every `.md` file in the project tree, extracts `[text](target)` links,
// and verifies each internal target exists. External `http(s)://` links are skipped
// (rate-limit / flakiness is worse than the rot they'd catch).
//
// Run from project root:
//
//   ./scripts/check-links.scala
//
// Exit 0 = all green. Exit 1 = at least one broken link. Findings printed to stderr.

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.util.matching.Regex

object CheckLinks {

  // [text](target) — markdown link syntax. Capture target group.
  private val LinkRe: Regex = """\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)""".r

  // # foo  -- ## bar -- ATX heading; captures the heading text after the #s.
  private val HeadingRe: Regex = """^(#{1,6})\s+(.+?)\s*#*\s*$""".r

  private val SkipDirs: Set[String] =
    Set(".git", ".madrileno-mcp", "target", ".bsp", ".bloop", ".scala-build", ".metals", ".idea", ".vscode", "logs", "node_modules")

  def main(args: Array[String]): Unit = {
    val root = Paths.get(".").toAbsolutePath.normalize
    val mdFiles = walkMd(root.toFile)
    val findings = mdFiles.flatMap(file => checkFile(file, root))
    findings.foreach(f => System.err.println(f))
    if (findings.nonEmpty) {
      System.err.println(s"\n${findings.size} broken link(s) found across ${mdFiles.size} markdown file(s).")
      sys.exit(1)
    } else {
      println(s"All links resolved across ${mdFiles.size} markdown file(s).")
    }
  }

  private def walkMd(dir: File): List[File] =
    if (!dir.isDirectory) Nil
    else
      Option(dir.listFiles).getOrElse(Array.empty[File]).toList.flatMap { f =>
        if (f.isDirectory) {
          if (SkipDirs.contains(f.getName)) Nil else walkMd(f)
        } else if (f.getName.endsWith(".md")) List(f)
        else Nil
      }

  private def checkFile(file: File, root: Path): List[String] = {
    val content = stripCode(Files.readString(file.toPath))
    val srcDisp = root.relativize(file.toPath).toString
    LinkRe.findAllMatchIn(content).toList.flatMap { m =>
      val target = m.group(1)
      validate(target, file, root) match {
        case Some(reason) => Some(s"$srcDisp: [${m.matched}] → $reason")
        case None         => None
      }
    }
  }

  // Strip fenced code blocks (``` ... ```) entirely. For inline code spans (`...`),
  // keep the visible text but neutralize markdown-link punctuation inside (`[ ] ( )`)
  // so code samples like `Page[AuctionDto]` or `[T](x)` don't trip LinkRe — and headings
  // like `## \`AuthContext\`` still slug to a non-empty anchor.
  private def stripCode(s: String): String = {
    val (filtered, _) = s.split('\n').toList.foldLeft((Vector.empty[String], false)) { case ((acc, inFence), line) =>
      val trimmed = line.trim
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) (acc, !inFence)
      else if (inFence) (acc, inFence)
      else (acc :+ line, inFence)
    }
    val inlineRe = """`[^`\n]*`""".r
    inlineRe.replaceAllIn(
      filtered.mkString("\n"),
      m => Regex.quoteReplacement(m.matched.stripPrefix("`").stripSuffix("`").replaceAll("[\\[\\]()]", ""))
    )
  }

  private def validate(target: String, source: File, root: Path): Option[String] = {
    // Skip externals + in-page anchors + mailto / tel etc.
    if (target.startsWith("http://") || target.startsWith("https://")) None
    else if (target.startsWith("mailto:") || target.startsWith("tel:")) None
    else if (target.startsWith("#")) checkAnchorInFile(source, target.drop(1))
    else {
      val (pathPart, anchor) = target.indexOf('#') match {
        case -1 => (target, "")
        case i  => (target.substring(0, i), target.substring(i + 1))
      }
      val resolved =
        if (pathPart.startsWith("/")) root.resolve(pathPart.drop(1)).normalize
        else source.toPath.getParent.resolve(pathPart).normalize
      if (!resolved.startsWith(root)) Some(s"path escapes repository root: $pathPart")
      else if (!Files.exists(resolved)) Some(s"path not found: $pathPart")
      else if (anchor.isEmpty) None
      else if (!resolved.toString.endsWith(".md")) None // only check anchors in markdown targets
      else checkAnchorInFile(resolved.toFile, anchor)
    }
  }

  private def checkAnchorInFile(file: File, anchor: String): Option[String] = {
    if (!file.exists() || !file.getName.endsWith(".md")) return None
    // GitHub disambiguates duplicate headings: the second `## Install` renders as `#install-1`,
    // the third as `#install-2`, etc. Track per-base-slug occurrence to match.
    // stripCode first so shell-style `# foo` comments inside fenced blocks don't register as headings.
    val headings = stripCode(Files.readString(file.toPath)).split('\n').toList
      .collect { case HeadingRe(_, text) => slug(text) }
      .foldLeft((Map.empty[String, Int], Set.empty[String])) { case ((counts, acc), base) =>
        val n   = counts.getOrElse(base, 0)
        val ank = if (n == 0) base else s"$base-$n"
        (counts.updated(base, n + 1), acc + ank)
      }
      ._2
    if (headings.contains(anchor)) None else Some(s"anchor not found: #$anchor in ${file.getName}")
  }

  // GitHub-flavoured slug: lowercase, strip code-fence chars and punctuation except hyphens/underscores,
  // collapse whitespace to single hyphens.
  private def slug(heading: String): String = {
    val lower    = heading.toLowerCase
    val stripped = lower.replaceAll("[^\\w\\- ]", "")
    stripped.trim.replaceAll("\\s+", "-")
  }
}
