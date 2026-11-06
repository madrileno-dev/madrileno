package app

object MainClass {
  def find(classes: os.Path): Either[String, String] = entryPoint(classes, "Main")

  def migrate(classes: os.Path): Either[String, String] = entryPoint(classes, "MigrateMain")

  private def entryPoint(classes: os.Path, simpleName: String): Either[String, String] = {
    val classFile  = s"$simpleName.class"
    val candidates = os
      .walk(classes)
      .filter(p => p.last == classFile && p.segments.toSeq.dropRight(1).lastOption.contains("main"))
      .map(p => p.relativeTo(classes).segments.mkString(".").stripSuffix(".class"))
      .toList
      .sorted
    candidates match {
      case single :: Nil => Right(single)
      case Nil           => Left(s"no */main/$classFile under $classes")
      case several       => Left(s"more than one */main/$classFile under $classes: ${several.mkString(", ")}")
    }
  }
}
