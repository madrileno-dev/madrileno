package architecture

import scala.meta.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.annotation.tailrec

object Trees {
  def topLevel(source: Source): List[Stat] = flatten(source.stats)

  def members(template: Template): List[Stat] = template.body.stats

  def typeName(tpe: Type): String = tpe match {
    case Type.Name(name)                 => name
    case Type.Select(_, name)            => name.value
    case Type.Apply.After_4_6_0(base, _) => typeName(base)
    case Type.Annotate(base, _)          => typeName(base)
    case other                           => other.syntax
  }

  def parentNames(template: Template): List[String] = template.inits.map(init => typeName(init.tpe))

  def closure(names: Set[String], parents: Map[String, List[String]]): Set[String] = {
    val next = names ++ names.flatMap(name => parents.getOrElse(name, Nil).filter(parents.contains))
    if (next == names) names else closure(next, parents)
  }

  def argument(
    args: List[Term],
    name: String,
    position: Int
  ): Option[Term] =
    args
      .collectFirst { case Term.Assign(Term.Name(`name`), value) => value }
      .orElse(args.lift(position).filter {
        case _: Term.Assign => false
        case _              => true
      })

  def isPrivate(mods: List[Mod]): Boolean = mods.exists {
    case _: Mod.Private => true
    case _              => false
  }

  def isSealed(mods: List[Mod]): Boolean = mods.exists {
    case _: Mod.Sealed => true
    case _             => false
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

  def codeLines(tree: Tree): Int = codeTokens(tree).flatMap(token => token.pos.startLine to token.pos.endLine).distinct.size

  def codeDigest(tree: Tree, rootPackage: String): String = {
    val root = rootPackage.split('.').toList.flatMap(segment => List(".", segment)).drop(1)
    @tailrec
    def normalize(
      rest: List[String],
      previous: String,
      done: List[String]
    ): List[String] = rest match {
      case Nil                                                                                         => done.reverse
      case _ if rest.startsWith(root) && (previous == "package" || rest.lift(root.size).contains(".")) =>
        normalize(rest.drop(root.size), "<root>", "<root>" :: done)
      case head :: tail => normalize(tail, head, head :: done)
    }
    val code = normalize(codeTokens(tree).map(_.text).toList, "", Nil).mkString(" ")
    hash(code)
  }

  def hash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).take(12).map(byte => f"$byte%02x").mkString

  def compact(tree: Tree): String = codeTokens(tree).map(_.text).mkString

  private def codeTokens(tree: Tree): Seq[Token] =
    tree.tokens.filter {
      case _: Token.Comment => false
      case token            => token.text.trim.nonEmpty
    }

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
