#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep org.scalameta::scalameta:4.17.4
//> using dep com.lihaoyi::mainargs:0.7.8
//> using file architecture

import architecture.*
import mainargs.{arg, main, Flag, ParserForMethods}

object ArchitectureMain {
  @main(doc = "Map a Madrileño project's architecture: modules, dependencies, events, tables and drift warnings.")
  def run(
    @arg(doc = "project root (default: the current directory)")
    project: String = ".",
    @arg(doc = "compare the merge-base of this ref and HEAD with the working tree")
    diff: Option[String] = None,
    @arg(doc = "print Mermaid: the diff for a PR description with --diff, the full map without it")
    mermaid: Flag,
    @arg(doc = "print the model as JSON")
    json: Flag,
    @arg(doc = "where to write the HTML (default: <project>/target/architecture/)")
    out: Option[String] = None,
    @arg(doc = "exit 1 when there are warnings or unclassified items (only new ones with --diff)")
    strict: Flag
  ): Unit = {
    val root = os.Path(project, os.pwd)
    Options
      .outputOf(mermaid.value, json.value, out, root, diff.isDefined)
      .flatMap(o => Cli.run(Options(root, diff, o, strict.value))) match {
      case Left(message) =>
        System.err.println(s"architecture: $message")
        sys.exit(2)
      case Right(outcome) =>
        if (outcome.stdout.nonEmpty) print(outcome.stdout)
        outcome.written.foreach(path => println(s"Wrote $path"))
        if (outcome.strictFailures.nonEmpty) {
          System.err.println(s"architecture: ${outcome.strictFailures.size} warning(s) or unclassified item(s):")
          outcome.strictFailures.foreach(failure => System.err.println(s"  $failure"))
          sys.exit(1)
        }
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
