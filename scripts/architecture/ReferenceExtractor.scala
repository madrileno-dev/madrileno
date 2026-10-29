package architecture

import scala.meta.{Member as _, *}

object ReferenceExtractor {
  private final case class RawReference(
    from: Owner,
    targetPath: String,
    symbol: String,
    loc: Loc)

  def extract(
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  ): List[Reference] = {
    val topPackages = files.map(_.pkg.split('/').head).filter(_.nonEmpty).toSet
    val raw         = files.flatMap { file =>
      val from = ownership.ownerOf(file.pkg)
      imports(file, from, rootPackage, topPackages) ++ qualified(file, from, rootPackage)
    }
    val platformContract = raw.flatMap {
      case RawReference(Owner.Platform(_), path, symbol, _) if symbol != "*" && symbol != "given" =>
        ownership.ownerOf(path) match {
          case Owner.Module(id) => Some(id -> symbol)
          case _                => None
        }
      case _ => None
    }.toSet
    raw
      .flatMap { ref =>
        (ref.from, ownership.ownerOf(ref.targetPath)) match {
          case (Owner.Module(from), Owner.Module(to)) if from != to && !platformContract.contains(to -> ref.symbol) =>
            Some(Reference(from, to, layer(to, ref.targetPath), ref.symbol, ref.loc))
          case _ => None
        }
      }
      .distinctBy(r => (r.from, r.to, r.layer, r.symbol))
      .sortBy(r => (r.from, r.to, r.layer, r.symbol))
  }

  private def layer(moduleId: String, targetPath: String): String =
    if (targetPath == moduleId) "module" else targetPath.stripPrefix(s"$moduleId/").split('/').head

  private def imports(
    file: ParsedFile,
    from: Owner,
    rootPackage: String,
    topPackages: Set[String]
  ): List[RawReference] =
    file.tree.collect { case importStat: Import => importStat.importers }.flatten.flatMap { importer =>
      val ref = importer.ref.syntax
      Packages
        .relative(rootPackage, ref)
        .orElse(Option.when(file.opensRoot && topPackages(ref.split('.').head))(ref.replace('.', '/')))
        .toList
        .flatMap { path =>
          importer.importees.flatMap(symbolOf).map(RawReference(from, path, _, loc(file, importer)))
        }
    }

  private def qualified(
    file: ParsedFile,
    from: Owner,
    rootPackage: String
  ): List[RawReference] =
    file.tree
      .collect {
        case select: Term.Select if outermostQualified(select, rootPackage) => select
        case select: Type.Select if outermostQualified(select, rootPackage) => select
      }
      .flatMap { select =>
        val segments = select.syntax.stripPrefix(s"$rootPackage.").split('.').toList
        segments.lastOption.map(symbol => RawReference(from, segments.dropRight(1).mkString("/"), symbol, loc(file, select)))
      }

  private def outermostQualified(tree: Tree, rootPackage: String): Boolean =
    tree.syntax.startsWith(s"$rootPackage.") && !tree.parent.exists(parent => enclosesQualified(parent, tree, rootPackage)) &&
      !ancestors(tree).exists {
        case _: Import => true
        case _         => false
      }

  private def enclosesQualified(
    parent: Tree,
    tree: Tree,
    rootPackage: String
  ): Boolean = parent match {
    case pkg: Pkg                        => pkg.ref eq tree
    case _: Term.Select | _: Type.Select => parent.syntax.startsWith(s"$rootPackage.")
    case _                               => false
  }

  private def ancestors(tree: Tree): List[Tree] = tree.parent.toList.flatMap(parent => parent :: ancestors(parent))

  private def symbolOf(importee: Importee): Option[String] = importee match {
    case Importee.Name(name)      => Some(name.value)
    case Importee.Rename(name, _) => Some(name.value)
    case _: Importee.Wildcard     => Some("*")
    case _: Importee.Given        => Some("given")
    case _: Importee.GivenAll     => Some("given")
    case _                        => None
  }

  private def loc(file: ParsedFile, tree: Tree): Loc = Loc(file.source.projectPath, Parsing.lineOf(tree))
}
