package architecture

import scala.meta.{Member as _, *}

object WiredTypes {
  private final case class Definition(
    module: String,
    name: String,
    layer: String,
    parents: List[String])

  def mark(
    references: List[Reference],
    wirings: List[Wiring],
    modules: List[Module],
    files: List[ParsedFile],
    ownership: Ownership
  ): List[Reference] = {
    val definitions = files.flatMap { file =>
      ownership.ownerOf(file.pkg) match {
        case Owner.Module(id) =>
          file.tree.collect {
            case d: Defn.Class => Definition(id, d.name.value, layerOf(id, file.pkg), Trees.parentNames(d.templ))
            case d: Defn.Trait => Definition(id, d.name.value, layerOf(id, file.pkg), Trees.parentNames(d.templ))
          }
        case _ => Nil
      }
    }
    val covered = wirings
      .collect { case Wiring(member, consumer, Provider.FromModule(provider)) => (consumer, provider, member) }
      .groupBy(
        (
          consumer,
          provider,
          _
        ) => (consumer, provider)
      )
      .map { case ((consumer, provider), members) =>
        val declared = members
          .flatMap(
            (
              _,
              _,
              member
            ) => modules.find(_.id == consumer).flatMap(_.consumes.find(_.name == member))
          )
          .map(m => baseName(m.tpe))
        val types = withSupertypes(declared.toSet, definitions.filter(_.module == provider))
        (consumer, provider) -> (types, definitions.filter(d => d.module == provider && types(d.name)).map(_.layer).toSet)
      }
    references.map { reference =>
      val (types, layers) = covered.getOrElse((reference.from, reference.to), (Set.empty[String], Set.empty[String]))
      reference.copy(wired = types(reference.symbol) || (reference.symbol == "*" && layers(reference.layer)))
    }
  }

  private def withSupertypes(names: Set[String], definitions: List[Definition]): Set[String] = {
    val next = names ++ definitions.filter(d => names(d.name)).flatMap(_.parents)
    if (next == names) names else withSupertypes(next, definitions)
  }

  private def baseName(tpe: String): String = tpe.takeWhile(_ != '[').split('.').last.trim

  private def layerOf(moduleId: String, pkg: String): String =
    if (pkg == moduleId) "module" else pkg.stripPrefix(s"$moduleId/").split('/').head
}
