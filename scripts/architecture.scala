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
    ProjectInput.load(project) match {
      case Right(input) => println(upickle.default.write(Extractor.extract(input), indent = 2))
      case Left(error)  => System.err.println(s"architecture: $error")
    }
  }
}
