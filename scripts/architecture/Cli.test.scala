package architecture

class CliSuite extends munit.FunSuite {
  private def project(files: (String, String)*): os.Path = {
    val dir = os.temp.dir()
    files.foreach { case (path, content) => os.write(dir / os.RelPath(path), content, createFolders = true) }
    dir
  }

  private val clean = project(
    "src/main/scala/app/main/ApplicationLoader.scala" -> "package app.main\nclass ApplicationLoader extends UserModule",
    "src/main/scala/app/user/UserModule.scala"        -> "package app.user\ntrait UserModule"
  )

  test("default html paths and writing the page") {
    assertEquals(Options.defaultHtml(clean, isDiff = false), clean / "target" / "architecture" / "architecture.html")
    assertEquals(Options.defaultHtml(clean, isDiff = true), clean / "target" / "architecture" / "architecture-diff.html")
    val outcome = Cli.run(Options(clean, None, Output.Html(Options.defaultHtml(clean, isDiff = false)), strict = false)).toOption.get
    assertEquals(outcome.written, Some(Options.defaultHtml(clean, isDiff = false)))
    assert(os.read(outcome.written.get).contains("<title>Architecture: working tree</title>"))
  }

  test("strict lists warnings in full mode") {
    val dirty = project(
      "src/main/scala/app/main/ApplicationLoader.scala" -> "package app.main\nclass ApplicationLoader",
      "src/main/scala/app/user/UserModule.scala"        -> "package app.user\ntrait UserModule"
    )
    val outcome = Cli.run(Options(dirty, None, Output.Json, strict = true)).toOption.get
    assertEquals(outcome.strictFailures, List("src/main/scala/app/user/UserModule.scala:2 UserModule is not mixed into ApplicationLoader"))
  }

  test("a missing project is a Left") {
    val missing = os.temp.dir() / "nope"
    assertEquals(Cli.run(Options(missing, None, Output.Json, strict = false)), Left(s"$missing does not exist"))
  }

  test("a directory without src/main/scala is a Left, so --strict cannot pass vacuously") {
    val empty = os.temp.dir()
    assertEquals(Cli.run(Options(empty, None, Output.Json, strict = true)), Left(s"no src/main/scala under $empty; is this a Madrileño project?"))
  }

  test("a project without a *Module trait is a Left, so --strict cannot pass vacuously") {
    val plain = project("src/main/scala/app/main/ApplicationLoader.scala" -> "package app.main\nclass ApplicationLoader")
    assertEquals(
      Cli.run(Options(plain, None, Output.Json, strict = true)),
      Left(s"no *Module traits under $plain/src/main/scala; is this a Madrileño project?")
    )
  }

  test("the base label names the ref and its short sha, once when the ref is the sha") {
    assertEquals(Cli.baseLabel("main", "abc1234def"), "main@abc1234")
    assertEquals(Cli.baseLabel("abc1234", "abc1234def"), "abc1234")
  }

  test("an error reading the base names the base revision") {
    val dir                = project("src/main/scala/app/user/UserModule.scala" -> "package app.user\ntrait UserModule")
    def git(args: String*) = os.proc(Seq("git", "-c", "user.name=t", "-c", "user.email=t@t") ++ args).call(cwd = dir)
    git("init", "-q", "-b", "main")
    git("add", ".")
    git("commit", "-q", "-m", "init")
    os.write(
      dir / "src" / "main" / "scala" / "app" / "main" / "ApplicationLoader.scala",
      "package app.main\nclass ApplicationLoader extends UserModule",
      createFolders = true
    )
    val error = Cli.run(Options(dir, Some("main"), Output.Json, strict = false)).left.toOption.get
    assert(error.startsWith("in main@"), error)
    assert(error.endsWith(": no main/ApplicationLoader.scala under src/main/scala; is this a Madrileño project?"), error)
  }

  test("mermaid without a diff prints the full map") {
    val outcome = Cli.run(Options(clean, None, Output.Mermaid, strict = false)).toOption.get
    assert(outcome.stdout.startsWith("### Architecture: `working tree`"), outcome.stdout)
  }

  test("--out goes with the HTML only") {
    assertEquals(
      Options.outputOf(mermaid = true, json = false, out = Some("x.html"), clean, isDiff = false),
      Left("--out writes the HTML, so it can't be combined with --mermaid or --json")
    )
    assertEquals(Options.outputOf(mermaid = false, json = true, out = None, clean, isDiff = false), Right(Output.Json))
    assertEquals(Options.outputOf(mermaid = true, json = true, out = None, clean, isDiff = false), Left("--mermaid and --json are exclusive"))
    assertEquals(
      Options.outputOf(mermaid = false, json = false, out = None, clean, isDiff = true),
      Right(Output.Html(Options.defaultHtml(clean, isDiff = true)))
    )
  }

  test("the JSON has every field, empty or not, so its shape doesn't depend on the project") {
    val json = ujson.read(Cli.run(Options(clean, None, Output.Json, strict = false)).toOption.get.stdout)
    assert(json.obj.contains("migrations") && json.obj.contains("changedOutside"), json.obj.keySet)
    val module = json("modules")(0)
    assert(module.obj.contains("changedFiles"), module.obj.keySet)
    assert(module("head").obj.contains("files"), module("head").obj.keySet)
  }

  test("the JSON ends with a newline, like the other outputs") {
    assert(Cli.run(Options(clean, None, Output.Json, strict = false)).toOption.get.stdout.endsWith("}\n"))
  }
}
