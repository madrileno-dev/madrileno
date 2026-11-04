package workspace

object AgentsMd {
  val Begin = "<!-- madrileno:workspace begin -->"
  val End   = "<!-- madrileno:workspace end -->"

  def block(members: List[Member], backendDir: String): String = {
    val table    = members.map(m => s"| `${m.dir}/` | ${m.role} | ${m.summary} |")
    val sections = members.flatMap(m => List(s"## ${m.dir} (${m.role})", "", m.section(backendDir), ""))
    (List(
      Begin,
      s"<!-- written by `(cd $backendDir && ./scripts/workspace.scala generate)` from each directory's ${Member.File}; edit those, not this block -->",
      "",
      "# Workspace",
      "",
      "| Directory | Role | What it is |",
      "| --- | --- | --- |"
    ) ++ table ++ List(
      "",
      "Each directory has its own `AGENTS.md` with its rules; it applies inside that directory. Read it before working there.",
      "Run a directory's scripts and package commands from inside it.",
      "",
      "The backend's router specs generate the API contract, and each app vendors it with the command in its section. After an API change, run the backend's tests, sync each app, then typecheck it: the errors point at the call sites to change.",
      ""
    ) ++ sections :+ End).mkString("\n")
  }

  def merge(existing: Option[String], block: String): String =
    existing match {
      case None                                                                          => block + "\n"
      case Some(text) if text.contains(Begin) && text.indexOf(End) > text.indexOf(Begin) =>
        text.substring(0, text.indexOf(Begin)) + block + text.substring(text.indexOf(End) + End.length)
      case Some(text) => text.stripTrailing + "\n\n" + block + "\n"
    }
}
