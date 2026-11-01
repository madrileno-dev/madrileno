package architecture

object Packages {
  def relative(rootPackage: String, declared: String): Option[String] =
    if (rootPackage.isEmpty) Some(declared.replace('.', '/'))
    else if (declared == rootPackage) Some("")
    else if (declared.startsWith(s"$rootPackage.")) Some(declared.stripPrefix(s"$rootPackage.").replace('.', '/'))
    else None
}
