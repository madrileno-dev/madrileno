package app

object Classpath {
  val file: os.RelPath = os.rel / "target" / "console-classpath"

  private val hint = "run `sbt --client compile` first"

  def read(root: os.Path): Either[String, String] = {
    val path = root / file
    if (!os.isFile(path)) Left(s"no cached classpath at $file: $hint")
    else {
      val classpath = os.read(path).trim
      val classes   = os.Path(classpath.takeWhile(_ != ':'))
      if (os.isDir(classes)) Right(classpath)
      else Left(s"the classes directory on the cached classpath is gone ($classes): $hint")
    }
  }

  def classesDir(classpath: String): os.Path = os.Path(classpath.takeWhile(_ != ':'))
}
