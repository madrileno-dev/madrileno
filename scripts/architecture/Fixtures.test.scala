package architecture

object Fixtures {
  def src(packagePath: String, content: String): SourceFile =
    SourceFile(packagePath, s"src/main/scala/app/$packagePath", content)

  def migration(name: String, content: String): SourceFile =
    SourceFile(name, s"src/main/resources/db/migration/$name", content)

  def project(files: SourceFile*): ProjectInput =
    ProjectInput("app", files.toList, Nil)

  def projectWithMigrations(files: List[SourceFile], migrations: List[SourceFile]): ProjectInput =
    ProjectInput("app", files, migrations)

  def parsed(files: SourceFile*): List[ParsedFile] =
    Parsing.parseAll(project(files*)).value

  lazy val diffBase: ArchModel = Extractor.extract(
    project(
      src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
      src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
      src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}")
    )
  )

  lazy val diffHead: ArchModel = Extractor.extract(
    project(
      src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with OrderModule"),
      src(
        "user/UserModule.scala",
        "package app.user\n\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n  private def helper = 1\n}"
      ),
      src(
        "order/OrderModule.scala",
        "package app.order\ntrait OrderModule {\n  lazy val userRepository: UserRepository\n  private val s = wire[OrderService]\n}"
      ),
      src("order/services/OrderService.scala", "package app.order.services\nclass OrderService(r: UserRepository)")
    )
  )
}
