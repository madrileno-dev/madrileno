package architecture

class ParsingSuite extends munit.FunSuite {
  test("derives the package relative to the root") {
    val files = Fixtures.parsed(Fixtures.src("auth/services/A.scala", "package app.auth.services\nclass A"))
    assertEquals(files.map(_.pkg), List("auth/services"))
  }

  test("an unparseable file becomes one unclassified item and the rest still parse") {
    val result = Parsing.parseAll(
      Fixtures.project(Fixtures.src("auth/Bad.scala", "package app.auth\nclass {"), Fixtures.src("auth/Good.scala", "package app.auth\nclass Good"))
    )
    assertEquals(result.value.map(_.source.packagePath), List("auth/Good.scala"))
    assertEquals(result.unclassified.map(_.key), List("unparseable:auth/Bad.scala"))
    assertEquals(result.unclassified.head.loc.path, "src/main/scala/app/auth/Bad.scala")
  }

  test("a file outside the root package is unclassified") {
    val result = Parsing.parseAll(Fixtures.project(Fixtures.src("x/X.scala", "package other.x\nclass X")))
    assertEquals(result.unclassified.map(_.key), List("outside-root:x/X.scala"))
  }

  test("load discovers the root package from main/ApplicationLoader.scala") {
    val dir = os.temp.dir()
    os.write(
      dir / "src" / "main" / "scala" / "com" / "acme" / "main" / "ApplicationLoader.scala",
      "package com.acme.main\nclass ApplicationLoader",
      createFolders = true
    )
    os.write(dir / "src" / "main" / "resources" / "db" / "migration" / "V1__a.sql", "create table a(id int);", createFolders = true)
    val input = ProjectInput.load(dir).toOption.get
    assertEquals(input.rootPackage, "com.acme")
    assertEquals(input.scalaFiles.map(_.packagePath), List("main/ApplicationLoader.scala"))
    assertEquals(input.migrations.map(_.packagePath), List("V1__a.sql"))
  }

  test("load returns empty without src/main/scala and Left without an ApplicationLoader") {
    val dir = os.temp.dir()
    assertEquals(ProjectInput.load(dir), Right(ProjectInput.empty))
    os.makeDir.all(dir / "src" / "main" / "scala" / "app")
    assert(ProjectInput.load(dir).isLeft)
  }
}
