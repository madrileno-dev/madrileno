package workspace

sealed trait Change {
  def path: os.RelPath
  def describe: String
}

object Change {
  final case class Write(path: os.RelPath, content: String) extends Change {
    def describe: String = s"wrote $path"
  }
  final case class Link(path: os.RelPath, target: os.RelPath) extends Change {
    def describe: String = s"linked $path -> $target"
  }
  final case class Unlink(path: os.RelPath) extends Change {
    def describe: String = s"removed $path"
  }
}

object Plan {
  private val Skills       = os.rel / ".agents" / "skills"
  private val ClaudeSkills = os.rel / ".claude" / "skills"
  private val ManagedLink  = """\.\./\.\./[^/]+/\.agents/skills/[^/]+""".r
  private val Import       = "@AGENTS.md"

  def changes(root: os.Path, backendDir: String): Either[String, List[Change]] =
    for {
      members   <- members(root, backendDir)
      perMember <- sequence(members.map(m => read(root / m.dir / ".mcp.json").fold(Right(Nil))(McpServers.read(m.dir, _))))
      servers   <- McpServers.combine(perMember)
      mcpJson   <-
        if (servers.isEmpty && !os.exists(root / ".mcp.json")) Right(None) else McpServers.mergeJson(read(root / ".mcp.json"), servers).map(Some(_))
      links  <- skillLinks(root, members)
      claude <- claudeLink(root)
    } yield {
      val codex = Option
        .when(servers.nonEmpty || os.exists(root / ".codex" / "config.toml"))(McpServers.mergeToml(read(root / ".codex" / "config.toml"), servers))
      val files = List(
        Some(os.rel / "AGENTS.md" -> AgentsMd.merge(read(root / "AGENTS.md"), AgentsMd.block(members, backendDir))),
        Some(os.rel / "CLAUDE.md" -> claudeMd(read(root / "CLAUDE.md"))),
        mcpJson.map(os.rel / ".mcp.json" -> _),
        codex.map(os.rel / ".codex" / "config.toml" -> _)
      ).flatten.collect { case (path, content) if !read(root / path).contains(content) => Change.Write(path, content) }
      files ++ links ++ claude
    }

  def apply(root: os.Path, changes: List[Change]): Unit =
    changes.foreach {
      case Change.Write(path, content) => os.write.over(root / path, content, createFolders = true)
      case Change.Link(path, target)   =>
        if (os.exists(root / path, followLinks = false)) os.remove(root / path)
        os.makeDir.all((root / path) / os.up)
        os.symlink(root / path, target)
      case Change.Unlink(path) => os.remove(root / path)
    }

  private def members(root: os.Path, backendDir: String): Either[String, List[Member]] =
    sequence(
      os.list(root)
        .filter(dir => os.isDir(dir) && !dir.last.startsWith(".") && os.isFile(dir / Member.File))
        .map(dir => Member.parse(dir.last, os.read(dir / Member.File)))
        .toList
    ).flatMap { members =>
      if (members.exists(m => m.dir == backendDir && m.role == "backend"))
        Right(members.sortBy(m => (m.dir != backendDir, m.dir)))
      else Left(s"$root has no backend member $backendDir: it needs $backendDir/${Member.File} with role: backend")
    }

  private def skillLinks(root: os.Path, members: List[Member]): Either[String, List[Change]] = {
    val wanted = members.flatMap { m =>
      val dir = root / m.dir / Skills
      if (os.isDir(dir)) os.list(dir).filter(os.isDir).map(s => s.last -> os.RelPath(s"../../${m.dir}/$Skills/${s.last}")).toList
      else Nil
    }
    val clashes = wanted.groupBy(_._1).collect { case (name, links) if links.size > 1 => name }
    val dir     = root / Skills
    if (clashes.nonEmpty) Left(s"two members have a skill of the same name: ${clashes.toList.sorted.mkString(", ")}")
    else if (os.exists(dir, followLinks = false) && !os.isDir(dir, followLinks = false)) Left(s"$Skills at the root is not a directory")
    else {
      val existing = if (os.isDir(dir)) os.list(dir).toList else Nil
      val blocked  = wanted.collect { case (name, _) if existing.exists(p => p.last == name && !os.isLink(p)) => name }
      if (blocked.nonEmpty) Left(s"$Skills/${blocked.mkString(", ")} at the root is not a link; move it or rename the member's skill")
      else {
        val links = wanted.collect {
          case (name, target) if !existing.exists(p => p.last == name && os.readLink(p).toString == target.toString) =>
            Change.Link(Skills / name, target)
        }
        val stale = existing.collect {
          case p if os.isLink(p) && ManagedLink.matches(os.readLink(p).toString) && !wanted.exists(_._1 == p.last) =>
            Change.Unlink(Skills / p.last)
        }
        Right(links ++ stale)
      }
    }
  }

  private def claudeLink(root: os.Path): Either[String, List[Change]] = {
    val path   = root / ClaudeSkills
    val target = os.RelPath("../.agents/skills")
    if (os.isLink(path) && os.readLink(path).toString == target.toString) Right(Nil)
    else if (os.isLink(path) || !os.exists(path, followLinks = false)) Right(List(Change.Link(ClaudeSkills, target)))
    else Left(s"$ClaudeSkills at the root is not a link to $target; move its skills into $Skills and remove it")
  }

  private def claudeMd(existing: Option[String]): String =
    existing match {
      case None                                                      => s"$Import\n"
      case Some(text) if text.linesIterator.exists(_.trim == Import) => text
      case Some(text)                                                => s"${text.stripTrailing}\n\n$Import\n"
    }

  private def read(path: os.Path): Option[String] = Option.when(os.isFile(path))(os.read(path))

  private def sequence[A](values: List[Either[String, A]]): Either[String, List[A]] =
    values.foldRight(Right(Nil): Either[String, List[A]])((value, acc) => value.flatMap(a => acc.map(a :: _)))
}
