package architecture

import scala.meta.*

object Trees {
  def topLevel(source: Source): List[Stat] = flatten(source.stats)

  def members(template: Template): List[Stat] = template.body.stats

  def typeName(tpe: Type): String = tpe match {
    case Type.Name(name)        => name
    case Type.Select(_, name)   => name.value
    case Type.Apply(base, _)    => typeName(base)
    case Type.Annotate(base, _) => typeName(base)
    case other                  => other.syntax
  }

  def parentNames(template: Template): List[String] = template.inits.map(init => typeName(init.tpe))

  def isPrivate(mods: List[Mod]): Boolean = mods.exists {
    case _: Mod.Private => true
    case _              => false
  }

  def isOverride(mods: List[Mod]): Boolean = mods.exists {
    case _: Mod.Override => true
    case _               => false
  }

  def isAbstractOverride(mods: List[Mod]): Boolean = isOverride(mods) && mods.exists {
    case _: Mod.Abstract => true
    case _               => false
  }

  def patNames(pats: List[Pat]): List[String] = pats.collect { case Pat.Var(name) => name.value }

  def collapse(text: String, max: Int): String = {
    val single = text.replaceAll("\\s+", " ").trim
    if (single.length > max) s"${single.take(max - 1)}…" else single
  }

  def nonBlankLines(content: String): Int = content.linesIterator.count(_.trim.nonEmpty)

  def ancestors(tree: Tree): List[Tree] = tree.parent.toList.flatMap(parent => parent :: ancestors(parent))

  def enclosingName(tree: Tree): String =
    ancestors(tree)
      .collectFirst {
        case d: Defn.Class  => d.name.value
        case d: Defn.Object => d.name.value
        case d: Defn.Trait  => d.name.value
      }
      .getOrElse("")

  def stringLiteral(term: Term): Option[String] = term match {
    case Lit.String(value) => Some(value)
    case _                 => None
  }

  def loc(file: ParsedFile, tree: Tree): Loc = Loc(file.source.projectPath, Parsing.lineOf(tree))

  private def flatten(stats: List[Stat]): List[Stat] = stats.flatMap {
    case pkg: Pkg => flatten(pkg.body.stats)
    case stat     => List(stat)
  }
}
