package app

class MainClassSuite extends munit.FunSuite {
  private def classesWith(paths: String*): os.Path = {
    val dir = os.temp.dir()
    paths.foreach(p => os.write(dir / os.RelPath(p), "", createFolders = true))
    dir
  }

  test("finds the application's Main from the compiled classes, whatever the root package is") {
    val classes = classesWith("winecellar/main/Main.class", "winecellar/main/Main$.class", "winecellar/user/UserModule.class")
    assertEquals(MainClass.find(classes), Right("winecellar.main.Main"))
  }

  test("a two-segment root package works too") {
    val classes = classesWith("com/acme/shop/main/Main.class")
    assertEquals(MainClass.find(classes), Right("com.acme.shop.main.Main"))
  }

  test("no Main class is an error that names the directory") {
    val classes       = classesWith("winecellar/user/UserModule.class")
    val Left(message) = MainClass.find(classes): @unchecked
    assert(message.contains(classes.toString), message)
  }

  test("the migration entry point sits next to Main in the same package") {
    val classes = classesWith("winecellar/main/Main.class", "winecellar/main/MigrateMain.class")
    assertEquals(MainClass.migrate(classes), Right("winecellar.main.MigrateMain"))
  }

  test("a missing migration entry point is an error") {
    val classes = classesWith("winecellar/main/Main.class")
    assert(MainClass.migrate(classes).isLeft)
  }

  test("two candidates is an error that lists both") {
    val classes       = classesWith("a/main/Main.class", "b/main/Main.class")
    val Left(message) = MainClass.find(classes): @unchecked
    assert(message.contains("a.main.Main") && message.contains("b.main.Main"), message)
  }
}
