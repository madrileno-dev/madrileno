package architecture

final case class SourceFile(
  packagePath: String,
  projectPath: String,
  content: String)

final case class ProjectInput(
  rootPackage: String,
  scalaFiles: List[SourceFile],
  migrations: List[SourceFile])

object ProjectInput {
  val empty: ProjectInput = ProjectInput("", Nil, Nil)

  def load(projectRoot: os.Path): Either[String, ProjectInput] = {
    val scalaRoot = projectRoot / "src" / "main" / "scala"
    if (!os.isDir(scalaRoot)) Right(empty)
    else {
      os.walk(scalaRoot).filter(isApplicationLoader).sorted.toList match {
        case List(loader) =>
          val packageDir = loader / os.up / os.up
          Right(
            ProjectInput(
              packageDir.relativeTo(scalaRoot).segments.mkString("."),
              read(projectRoot, packageDir, "scala"),
              read(projectRoot, projectRoot / "src" / "main" / "resources" / "db" / "migration", "sql")
            )
          )
        case Nil     => Left("no main/ApplicationLoader.scala under src/main/scala; is this a Madrileño project?")
        case several => Left(s"several ApplicationLoader.scala files: ${several.map(_.relativeTo(projectRoot)).mkString(", ")}")
      }
    }
  }

  private def isApplicationLoader(path: os.Path): Boolean =
    path.last == "ApplicationLoader.scala" && (path / os.up).last == "main"

  private def read(
    projectRoot: os.Path,
    dir: os.Path,
    extension: String
  ): List[SourceFile] =
    if (!os.isDir(dir)) Nil
    else
      os.walk(dir)
        .filter(path => os.isFile(path) && path.ext == extension)
        .sorted
        .toList
        .map(path => SourceFile(path.relativeTo(dir).toString, path.relativeTo(projectRoot).toString, os.read(path)))
}
