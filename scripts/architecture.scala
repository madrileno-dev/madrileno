#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep org.scalameta::scalameta:4.17.4
//> using dep com.lihaoyi::mainargs:0.7.8
//> using file architecture

import architecture.*

object ArchitectureMain {
  def main(args: Array[String]): Unit = {
    val project = args.sliding(2).collectFirst { case Array("--project", path) => os.Path(path, os.pwd) }.getOrElse(os.pwd)
    val html    = args.sliding(2).collectFirst { case Array("--html", path) => os.Path(path, os.pwd) }
    val diff    = args.sliding(2).collectFirst { case Array("--diff", ref) => ref }
    ProjectInput.load(project) match {
      case Right(input) =>
        val head  = Extractor.extract(input)
        val model = diff match {
          case Some(ref) =>
            GitRevision
              .withRevision(project, ref)(rev => ProjectInput.load(rev.root).map(Extractor.extract))
              .flatten
              .map(base => ArchDiff.compare(base, head, ref, "working tree"))
              .getOrElse(ArchDiff.full(head, "working tree"))
          case None => ArchDiff.full(head, "working tree")
        }
        html match {
          case Some(path) => os.write.over(path, Html.render(model), createFolders = true)
          case None       => println(upickle.default.write(head, indent = 2))
        }
      case Left(error) => System.err.println(s"architecture: $error")
    }
  }
}
