package architecture

import scala.meta.{Member as _, *}

object WiringExtractor {
  def extract(
    modules: List[Module],
    files: List[ParsedFile],
    ownership: Ownership
  ): Extracted[List[Wiring]] = {
    val platformNames = files
      .filter(file =>
        ownership.ownerOf(file.pkg) match {
          case Owner.Module(_) => false
          case _               => true
        }
      )
      .flatMap(definedNames)
      .toSet
    val providers = modules.flatMap(module => module.provides.map(_.name -> module.id)).groupMap(_._1)(_._2)
    val resolved  = for {
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

  private def definedNames(file: ParsedFile): List[String] =
    file.tree.collect {
      case d: Defn.Val        => Trees.patNames(d.pats)
      case d: Decl.Val        => Trees.patNames(d.pats)
      case d: Defn.Def        => List(d.name.value)
      case d: Decl.Def        => List(d.name.value)
      case d: Defn.GivenAlias => List(d.name.value)
      case d: Decl.Given      => List(d.name.value)
      case d: Defn.Given      => List(d.name.value)
    }.flatten
}
