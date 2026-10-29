package architecture

import scala.meta.{Member as _, *}

object ExplainedReferences {
  private final case class Definition(
    module: String,
    name: String,
    layer: String,
    parents: List[String],
    signatureTypes: List[String])

  def mark(
    references: List[Reference],
    wirings: List[Wiring],
    modules: List[Module],
    files: List[ParsedFile],
    ownership: Ownership,
    eventTypes: Set[String]
  ): List[Reference] = {
    val definitions = files.flatMap { file =>
      ownership.ownerOf(file.pkg) match {
        case Owner.Module(id) =>
          file.tree.collect {
            case d: Defn.Class => Definition(id, d.name.value, Layers.of(id, file.pkg), Trees.parentNames(d.templ), signatureTypes(d.templ))
            case d: Defn.Trait => Definition(id, d.name.value, Layers.of(id, file.pkg), Trees.parentNames(d.templ), signatureTypes(d.templ))
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
        val ownDefinitions = definitions.filter(_.module == provider)
        val types          = withSupertypes(declared.toSet, ownDefinitions)
        val wiredDefs      = ownDefinitions.filter(d => types(d.name))
        (consumer, provider) -> (types ++ wiredDefs.flatMap(_.signatureTypes), wiredDefs.map(_.layer).toSet)
      }
    references.map { reference =>
      val (types, layers) = covered.getOrElse((reference.from, reference.to), (Set.empty[String], Set.empty[String]))
      val explanation     =
        if (types(reference.symbol) || (reference.symbol == "*" && layers(reference.layer))) Some(Explanation.Wiring)
        else if (eventTypes(reference.symbol)) Some(Explanation.Event)
        else None
      reference.copy(explainedBy = explanation)
    }
  }

  private def signatureTypes(template: Template): List[String] =
    Trees.members(template).flatMap {
      case d: Defn.Def if isPublic(d.mods) => typeNames(d.paramClauseGroups.flatMap(_.paramClauses.flatMap(_.values.flatMap(_.decltpe))) ++ d.decltpe)
      case d: Decl.Def if isPublic(d.mods) => typeNames(d.paramClauseGroups.flatMap(_.paramClauses.flatMap(_.values.flatMap(_.decltpe))) :+ d.decltpe)
      case _                               => Nil
    }

  private def typeNames(types: List[Type]): List[String] = types.flatMap(_.collect { case Type.Name(name) => name })

  private def isPublic(mods: List[Mod]): Boolean = mods.forall {
    case _: Mod.Private | _: Mod.Protected => false
    case _                                 => true
  }

  private def withSupertypes(names: Set[String], definitions: List[Definition]): Set[String] = {
    val next = names ++ definitions.filter(d => names(d.name)).flatMap(_.parents)
    if (next == names) names else withSupertypes(next, definitions)
  }

  private def baseName(tpe: String): String = tpe.takeWhile(_ != '[').split('.').last.trim
}
