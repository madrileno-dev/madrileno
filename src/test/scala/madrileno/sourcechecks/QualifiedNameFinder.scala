package madrileno.sourcechecks

import scala.meta.*

object QualifiedNameFinder {
  private val InScope = Set(
    "Any",
    "AnyRef",
    "AnyVal",
    "Array",
    "BigDecimal",
    "BigInt",
    "Boolean",
    "Byte",
    "Char",
    "Class",
    "Double",
    "Either",
    "Equals",
    "Error",
    "Exception",
    "Float",
    "Function",
    "Int",
    "Iterable",
    "Iterator",
    "LazyList",
    "Left",
    "List",
    "Long",
    "Map",
    "Nil",
    "None",
    "Nothing",
    "Null",
    "Option",
    "Ordering",
    "PartialFunction",
    "Product",
    "Range",
    "Right",
    "Seq",
    "Serializable",
    "Set",
    "Short",
    "Some",
    "String",
    "StringBuilder",
    "Symbol",
    "Throwable",
    "Tuple",
    "Unit",
    "Vector"
  )

  def roots(sources: List[ParsedSource]): Set[String] =
    Set("java", "javax", "scala") ++ sources.flatMap { source =>
      source.tree.collect {
        case pkg: Pkg if outermost(pkg)               => segments(pkg.ref).flatMap(_.headOption)
        case importer: Importer if topLevel(importer) => segments(importer.ref).flatMap(_.headOption).filter(_.head.isLower)
      }.flatten
    }

  def in(source: ParsedSource, roots: Set[String]): List[SourceFinding] = {
    val defined  = definedNames(source.tree)
    val imported = importedPaths(source.tree)
    source.tree.collect {
      case ref @ (_: Term.Select | _: Type.Select) if !qualifier(ref) && !insideImport(ref) =>
        segments(ref).filter(_.headOption.exists(roots.contains)).flatMap { path =>
          val (packages, rest) = path.span(_.head.isLower)
          rest.headOption
            .filterNot { name =>
              InScope(name) || defined(name) || imported.getOrElse(name, Set.empty).exists(_ != packages.mkString("."))
            }
            .map(name => SourceFinding.at(source, ref, ref.pos.startLine + 1, (packages :+ name).mkString(".")))
        }
    }.flatten
  }

  private def segments(tree: Tree): Option[List[String]] = tree match {
    case Term.Select(qualifier, name) => segments(qualifier).map(_ :+ name.value)
    case Type.Select(qualifier, name) => segments(qualifier).map(_ :+ name.value)
    case Term.Name(name)              => Some(List(name))
    case _                            => None
  }

  private def qualifier(tree: Tree): Boolean = tree.parent.exists {
    case parent: Term.Select => parent.qual eq tree
    case parent: Type.Select => parent.qual eq tree
    case _                   => false
  }

  private def insideImport(tree: Tree): Boolean = tree.parent.exists {
    case _: Import | _: Export => true
    case pkg: Pkg              => pkg.ref eq tree
    case parent                => insideImport(parent)
  }

  private def topLevel(tree: Tree): Boolean =
    !ancestors(tree).exists {
      case _: Template | _: Term.Block | _: Defn => true
      case _                                     => false
    }

  private def outermost(pkg: Pkg): Boolean =
    !ancestors(pkg).exists {
      case _: Pkg => true
      case _      => false
    }

  private def ancestors(tree: Tree): List[Tree] = tree.parent.toList.flatMap(parent => parent :: ancestors(parent))

  private def definedNames(tree: Tree): Set[String] =
    tree
      .collect {
        case defn: Defn.Type   => List(defn.name.value)
        case defn: Defn.Val    => defn.pats.collect { case Pat.Var(name) => name.value }
        case defn: Defn.Def    => List(defn.name.value)
        case defn: Defn.Object => List(defn.name.value)
        case defn: Defn.Class  => List(defn.name.value)
        case defn: Defn.Trait  => List(defn.name.value)
        case defn: Defn.Enum   => List(defn.name.value)
      }
      .flatten
      .toSet

  private def importedPaths(tree: Tree): Map[String, Set[String]] =
    tree
      .collect { case importer: Importer =>
        importer.importees.collect {
          case Importee.Name(name)      => name.value -> importer.ref.syntax
          case Importee.Rename(_, name) => name.value -> importer.ref.syntax
        }
      }
      .flatten
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
}
