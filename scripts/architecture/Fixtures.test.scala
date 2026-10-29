package architecture

object Fixtures {
  def src(packagePath: String, content: String): SourceFile =
    SourceFile(packagePath, s"src/main/scala/app/$packagePath", content)

  def migration(name: String, content: String): SourceFile =
    SourceFile(name, s"src/main/resources/db/migration/$name", content)

  def project(files: SourceFile*): ProjectInput =
    ProjectInput("app", files.toList, Nil)

  def projectWithMigrations(files: List[SourceFile], migrations: List[SourceFile]): ProjectInput =
    ProjectInput("app", files, migrations)

  def parsed(files: SourceFile*): List[ParsedFile] =
    Parsing.parseAll(project(files*)).value
}
