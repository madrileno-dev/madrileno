package architecture

class GitRevisionSuite extends munit.FunSuite {
  private def git(dir: os.Path, args: String*) =
    os.proc(Seq("git", "-c", "user.name=t", "-c", "user.email=t@t") ++ args).call(cwd = dir)

  private def repo(): os.Path = {
    val dir = os.temp.dir()
    git(dir, "init", "-q", "-b", "main")
    os.write(dir / "README.md", "x")
    git(dir, "add", "README.md")
    git(dir, "commit", "-q", "-m", "init")
    dir
  }

  test("a base without src/main materializes empty, and the working tree is untouched") {
    val dir = repo()
    os.write(
      dir / "src" / "main" / "scala" / "app" / "main" / "ApplicationLoader.scala",
      "package app.main\nclass ApplicationLoader",
      createFolders = true
    )
    val before = git(dir, "status", "--porcelain").out.text()
    val loaded = GitRevision.withRevision(dir, "main")(rev => ProjectInput.load(rev.root))
    assertEquals(loaded, Right(Right(ProjectInput.empty)))
    assertEquals(git(dir, "status", "--porcelain").out.text(), before)
  }

  test("the base sees committed files only, and the temp dir is removed") {
    val dir = repo()
    os.write(
      dir / "src" / "main" / "scala" / "app" / "main" / "ApplicationLoader.scala",
      "package app.main\nclass ApplicationLoader",
      createFolders = true
    )
    git(dir, "add", "src")
    git(dir, "commit", "-q", "-m", "loader")
    os.write(dir / "src" / "main" / "scala" / "app" / "user" / "UserModule.scala", "package app.user\ntrait UserModule", createFolders = true)
    val (files, root) = GitRevision
      .withRevision(dir, "main")(rev => (ProjectInput.load(rev.root).toOption.get.scalaFiles.map(_.packagePath), rev.root))
      .toOption
      .get
    assertEquals(files, List("main/ApplicationLoader.scala"))
    assert(!os.exists(root))
  }

  test("bad ref and non-repository are Left, never exceptions") {
    assert(GitRevision.withRevision(repo(), "nope")(_ => ()).left.toOption.get.contains("cannot find a merge-base of 'nope'"))
    assert(GitRevision.withRevision(os.temp.dir(), "main")(_ => ()).left.toOption.get.contains("is not a git repository"))
  }
}
