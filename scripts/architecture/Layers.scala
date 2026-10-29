package architecture

object Layers {
  val Internal: Set[String] = Set("services", "repositories", "routers", "gateways")

  def of(moduleId: String, pkg: String): String =
    if (pkg == moduleId) "module" else pkg.stripPrefix(s"$moduleId/").split('/').head

  def kindOf(pkg: String): ComponentKind = pkg.split('/').lastOption match {
    case Some("services")     => ComponentKind.Service
    case Some("repositories") => ComponentKind.Repository
    case Some("routers")      => ComponentKind.Router
    case Some("gateways")     => ComponentKind.Gateway
    case _                    => ComponentKind.Other
  }
}

extension (owner: Owner) {
  def label: String = owner match {
    case Owner.Module(id)      => id
    case Owner.Platform(id)    => s"platform ($id)"
    case Owner.CompositionRoot => "main"
  }
}
