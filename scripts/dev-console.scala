#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default

// Launches a scala-cli REPL with the project's wire graph live. See `docs/scripts.md`.

// `__package__` is substituted with the auto-detected project package at launch,
// so the predef stays correct after `init-project.scala` has renamed the source
// tree (which itself doesn't walk into `scripts/`).
private val predefSource: String =
  """import cats.effect.IO
    |import cats.effect.unsafe.implicits.global
    |import __package__.main.*
    |import __package__.auth.domain.*
    |import __package__.user.domain.*
    |import __package__.utils.db.transactor.DB
    |
    |val app: ApplicationLoader = {
    |  val a = ConsoleApplication.boot()
    |  println(s"${a.appConfig.name} dev console — env=${a.appConfig.environment}")
    |  println("  app        the ApplicationLoader (transactor, repositories, services)")
    |  println("  run(io)    execute an IO[A] and return A")
    |  println("  db(action) execute a DB[A] inside a session")
    |  a
    |}
    |
    |def run[A](io: IO[A]): A    = io.unsafeRunSync()
    |def db[A](action: DB[A]): A = run(app.transactor.inSession(action))
    |""".stripMargin

@main def devConsole(): Unit = {
  val root          = os.pwd
  val classpathFile = root / "target" / "console-classpath"

  require(os.exists(root / "build.sbt"),
    s"$root doesn't look like the project root (no build.sbt)")
  if (!os.exists(classpathFile)) {
    Console.err.println(s"missing $classpathFile")
    Console.err.println("run `sbt compile` first — that hook writes the cached classpath (and ensures project classes exist).")
    sys.exit(1)
  }

  val packageDirs = os.list(root / "src" / "main" / "scala").filter(os.isDir(_))
  require(packageDirs.size == 1,
    s"expected exactly one package directory under src/main/scala/, found: ${packageDirs.map(_.last).mkString(", ")}")
  val packageName = packageDirs.head.last

  val cp = os.read(classpathFile).trim
  require(cp.nonEmpty, s"$classpathFile is empty")

  val envFromFile: Map[String, String] = {
    val f = root / ".env"
    if (!os.exists(f)) Map.empty
    else os.read(f).linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .flatMap { l =>
        l.split("=", 2) match {
          case Array(k, v) =>
            val unquoted = v.trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
            Some(k.trim -> unquoted)
          case _ => None
        }
      }.toMap
  }

  val predef     = predefSource.replace("__package__", packageName)
  val predefFile = os.temp(predef, prefix = "dev-console-predef-", suffix = ".scala")

  val rc = os.proc("scala-cli", "repl", "--scala", "3.9.0", "--classpath", cp, predefFile.toString).call(
    env = envFromFile ++ sys.env,
    stdin = os.Inherit,
    stdout = os.Inherit,
    stderr = os.Inherit,
    check = false
  ).exitCode
  sys.exit(rc)
}
