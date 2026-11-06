package app

object Launch {
  val javaOptions: Seq[String] = Seq("-Dotel.java.global-autoconfigure.enabled=true")

  def command(
    java: String,
    classpath: String,
    mainClass: String
  ): Seq[String] =
    (java +: javaOptions) ++ Seq("-cp", classpath, mainClass)

  def environment(inherited: Map[String, String], dotEnv: Map[String, String]): Map[String, String] =
    inherited ++ dotEnv
}
