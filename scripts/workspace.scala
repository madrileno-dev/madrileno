#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8
//> using file workspace
//> using mainClass WorkspaceMain

import mainargs.{arg, main, ParserForMethods}
import workspace.*

object WorkspaceMain {
  @main(doc = "Write AGENTS.md, CLAUDE.md, skill links and MCP config at the root of a workspace that holds this backend and its apps.")
  def generate(@arg(doc = "the workspace root (default: the parent directory)") root: String = ".."): Unit = {
    val (dir, backend) = locate(root)
    val changes        = plan(dir, backend)
    Plan.apply(dir, changes)
    if (changes.isEmpty) println(s"$dir is up to date")
    else changes.foreach(change => println(change.describe))
    sys.exit(0)
  }

  @main(doc = "Report whether the workspace root matches what generate would write. Exit 1 when it is stale.")
  def check(@arg(doc = "the workspace root (default: the parent directory)") root: String = ".."): Unit = {
    val (dir, backend) = locate(root)
    plan(dir, backend) match {
      case Nil =>
        println(s"$dir is up to date")
        sys.exit(0)
      case changes =>
        changes.foreach(change => println(s"stale: ${change.path}"))
        println(s"run `(cd $backend && ./scripts/workspace.scala generate)`")
        sys.exit(1)
    }
  }

  private def locate(root: String): (os.Path, String) = {
    if (!os.isFile(os.pwd / "build.sbt")) fail("run this from the backend root (no build.sbt here)")
    val dir = os.Path(root, os.pwd)
    if (!os.isDir(dir)) fail(s"$dir is not a directory")
    (dir, os.pwd.last)
  }

  private def plan(root: os.Path, backend: String): List[Change] =
    Plan.changes(root, backend).fold(fail, identity)

  private def fail(message: String): Nothing = {
    System.err.println(s"error: $message")
    sys.exit(2)
  }

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runEither(args.toIndexedSeq).left.foreach { usage =>
      System.err.println(usage)
      sys.exit(2)
    }
}
