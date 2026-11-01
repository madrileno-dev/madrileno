package app

class ClasspathSuite extends munit.FunSuite {
  test("a missing classpath file says to compile first") {
    val root          = os.temp.dir()
    val Left(message) = Classpath.read(root): @unchecked
    assert(message.contains("sbt --client compile"), message)
  }

  test("reads the cached classpath when its classes directory exists") {
    val root    = os.temp.dir()
    val classes = root / "target" / "scala-3.9.0" / "classes"
    os.makeDir.all(classes)
    os.write(root / "target" / "console-classpath", s"$classes:/some/lib.jar\n")
    assertEquals(Classpath.read(root), Right(s"$classes:/some/lib.jar"))
  }

  test("a classpath whose classes directory is gone says to compile first") {
    val root = os.temp.dir()
    os.makeDir.all(root / "target")
    os.write(root / "target" / "console-classpath", s"${root / "target" / "classes"}:/some/lib.jar")
    val Left(message) = Classpath.read(root): @unchecked
    assert(message.contains("sbt --client compile"), message)
  }
}
