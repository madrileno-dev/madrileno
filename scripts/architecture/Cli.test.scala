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

  test("the base label names the ref and its short sha, once when the ref is the sha") {
    assertEquals(Cli.baseLabel("main", "abc1234def"), "main@abc1234")
    assertEquals(Cli.baseLabel("abc1234", "abc1234def"), "abc1234")
  }
}
