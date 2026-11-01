package app

class LaunchSuite extends munit.FunSuite {
  test("the java command carries the classpath, the main class and the build's java options") {
    val cmd = Launch.command(java = "/jdk/bin/java", classpath = "/c:/l.jar", mainClass = "winecellar.main.Main")
    assertEquals(cmd.head, "/jdk/bin/java")
    assertEquals(cmd.last, "winecellar.main.Main")
    assert(cmd.containsSlice(Seq("-cp", "/c:/l.jar")))
    assert(cmd.contains("-Dotel.java.global-autoconfigure.enabled=true"), "javaOptions from build.sbt")
  }

  test("values from .env override the inherited environment, as sbt-dotenv does for reStart") {
    val env = Launch.environment(inherited = Map("PORT" -> "1", "HOME" -> "/h"), dotEnv = Map("PORT" -> "9000"))
    assertEquals(env, Map("PORT" -> "9000", "HOME" -> "/h"))
  }
}
