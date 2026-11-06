package app

object DotEnv {
  def parse(text: String): Map[String, String] =
    text.linesIterator.flatMap(assignment).toMap

  private def assignment(line: String): Option[(String, String)] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith("#")) None
    else {
      val withoutExport = if (trimmed.startsWith("export ")) trimmed.drop("export ".length).trim else trimmed
      withoutExport.indexOf('=') match {
        case -1 => None
        case i  => Some(withoutExport.take(i).trim -> value(withoutExport.drop(i + 1).trim))
      }
    }
  }

  private def value(raw: String): String =
    raw.headOption match {
      case Some(q @ ('\'' | '"')) if raw.length >= 2 && raw.indexOf(q, 1) > 0 => raw.substring(1, raw.indexOf(q, 1))
      case _                                                                  => raw.split(" #", 2).head.trim
    }
}
