package architecture

import scala.meta.{Member as _, *}

object WiringExtractor {
  def extract(
    modules: List[Module],
    files: List[ParsedFile],
    ownership: Ownership
  ): Extracted[List[Wiring]] = {
    val platformNames = providedByComposition(files, ownership)
    val providers     = modules.flatMap(module => module.provides.map(_.name -> module.id)).groupMap(_._1)(_._2)
    val resolved      = for {
      module <- modules
      member <- module.consumes
    } yield providers.getOrElse(member.name, Nil).filterNot(_ == module.id) match {
      case Nil if platformNames.contains(member.name) => Right(List(Wiring(member.name, module.id, Provider.FromPlatform)))
      case Nil                                        =>
        Left(
          Warning(
            WarningKind.UnprovidedMember,
            s"unprovided:${module.id}:${member.name}",
            s"${module.id} consumes ${member.name}: ${member.tpe} but nothing provides it",
            member.loc
          )
        )
      case ids => Right(ids.map(id => Wiring(member.name, module.id, Provider.FromModule(id))))
    }
    val notMixedIn = modules
      .filterNot(_.mixedIn)
      .map(module =>
        Warning(WarningKind.NotMixedIn, s"not-mixed-in:${module.id}", s"${module.traitName} is not mixed into ApplicationLoader", module.loc)
      )
    Extracted(resolved.collect { case Right(wirings) => wirings }.flatten, resolved.collect { case Left(warning) => warning } ++ notMixedIn, Nil)
  }

  private final case class Definition(parents: List[String], names: List[String])

  private def providedByComposition(files: List[ParsedFile], ownership: Ownership): Set[String] = {
    val definitions = files
      .filter(file =>
        ownership.ownerOf(file.pkg) match {
          case Owner.Module(_) => false
          case _               => true
        }
      )
      .flatMap(_.tree.collect {
        case d: Defn.Class  => d.name.value -> Definition(Trees.parentNames(d.templ), memberNames(d.templ) ++ ctorNames(d))
        case d: Defn.Trait  => d.name.value -> Definition(Trees.parentNames(d.templ), memberNames(d.templ))
        case d: Defn.Object => d.name.value -> Definition(Trees.parentNames(d.templ), memberNames(d.templ))
      })
      .toMap
    val reachable = closure(Set("ApplicationLoader").filter(definitions.contains), definitions)
    reachable.flatMap(name => definitions(name).names)
  }

  private def closure(names: Set[String], definitions: Map[String, Definition]): Set[String] = {
    val next = names ++ names.flatMap(name => definitions(name).parents.filter(definitions.contains))
    if (next == names) names else closure(next, definitions)
  }

  private def memberNames(template: Template): List[String] =
    Trees.members(template).flatMap {
      case d: Defn.Val        => Trees.patNames(d.pats)
      case d: Decl.Val        => Trees.patNames(d.pats)
      case d: Defn.Def        => List(d.name.value)
      case d: Decl.Def        => List(d.name.value)
      case d: Defn.GivenAlias => List(d.name.value)
      case d: Decl.Given      => List(d.name.value)
      case d: Defn.Given      => List(d.name.value)
      case _                  => Nil
    }

  private def ctorNames(cls: Defn.Class): List[String] =
    cls.ctor.paramClauses.flatMap(_.values).filter(isMemberParam(cls, _)).map(_.name.value).toList

  private def isMemberParam(cls: Defn.Class, param: Term.Param): Boolean =
    cls.mods.exists {
      case _: Mod.Case => true
      case _           => false
    } || param.mods.exists {
      case _: Mod.ValParam | _: Mod.VarParam => true
      case _                                 => false
    }
}
