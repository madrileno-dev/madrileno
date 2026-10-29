package architecture

import scala.meta.*

final case class Ownership(moduleIds: Set[String]) {
  def ownerOf(pkg: String): Owner =
    moduleIds.filter(id => pkg == id || pkg.startsWith(s"$id/")).maxByOption(_.length) match {
      case Some(id)                                         => Owner.Module(id)
      case None if pkg == "main" || pkg.startsWith("main/") => Owner.CompositionRoot
      case None                                             => Owner.Platform(pkg)
    }
}

object Ownership {
  def of(files: List[ParsedFile]): Ownership =
    Ownership(files.filter(file => moduleTraits(file).nonEmpty).map(_.pkg).toSet)

  def moduleTraits(file: ParsedFile): List[Defn.Trait] =
    Trees.topLevel(file.tree).collect { case t: Defn.Trait if t.name.value.endsWith("Module") => t }
}
