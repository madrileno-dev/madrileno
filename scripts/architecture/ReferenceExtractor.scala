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
    val raw              = rawReferences(files, ownership, rootPackage)
    val platformContract = raw.flatMap {
      case RawReference(owner @ Owner.Platform(_), path, symbol, _) if owner.inUtils && symbol != "*" && symbol != "given" =>
        ownership.ownerOf(path) match {
          case Owner.Module(id) => Some(id -> locate(id, path, symbol)._2)
          case _                => None
        }
      case _ => None
    }.toSet
    raw
      .flatMap { ref =>
        (ref.from, ownership.ownerOf(ref.targetPath)) match {
          case (Owner.Module(from), Owner.Module(to)) if from != to =>
            val (layer, symbol) = locate(to, ref.targetPath, ref.symbol)
            Option.unless(platformContract.contains(to -> symbol))(Reference(from, to, layer, symbol, ref.loc))
          case _ => None
        }
      }
      .distinctBy(r => (r.from, r.to, r.layer, r.symbol))
      .sortBy(r => (r.from, r.to, r.layer, r.symbol))
  }

  def outsideImports(
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  ): Map[String, List[OutsideImport]] = {
    val outside     = outsideFiles(files, ownership)
    val packages    = outside.groupBy(_.pkg)
    val definitions = outside.map(file => file -> Trees.topLevelDefinitions(file.tree)).toMap
    val names       = definitions.view.mapValues(_.map(_._1).distinct).toMap
    val givens      = definitions.view.mapValues(_.collect { case (name, stat) if isGiven(stat) => name }.distinct).toMap
    val defined     = outside.flatMap(file => names(file).map(file.pkg -> _)).toSet
    val byPath      = files.map(file => file.source.projectPath -> file).toMap
    rawReferences(files, ownership, rootPackage)
      .collect { case ref @ RawReference(Owner.Module(_), _, _, _) => ref }
      .groupBy(_.loc.path)
      .toList
      .flatMap { (path, refs) =>
        lazy val used = byPath(path).tree.collect { case name: Name => name.value }.toSet
        refs.flatMap { ref =>
          val segments = ref.targetPath.split('/').toList.filter(_.nonEmpty) ++ Option.unless(ref.symbol == "*" || ref.symbol == "given")(ref.symbol)
          packagesOf(ref.targetPath, ref.symbol).find(packages.contains).toList.flatMap { pkg =>
            val imported = segments.drop(pkg.split('/').count(_.nonEmpty)).headOption match {
              case Some(name)                    => Option.when(defined(pkg -> name))(name).toList
              case None if ref.symbol == "given" => packages(pkg).flatMap(givens)
              case None                          => packages(pkg).flatMap(names).filter(used)
            }
            imported.map(name => ref.from -> OutsideImport(pkg, name))
          }
        }
      }
      .collect { case (Owner.Module(id), imported) => id -> imported }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sortBy(imported => (imported.pkg, imported.name)))
      .toMap
  }

  def outsideParents(
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  ): Map[String, Map[String, List[OutsideImport]]] = {
    val outside    = outsideFiles(files, ownership)
    val defined    = outside.flatMap(file => Trees.topLevelDefinitions(file.tree).map(file.pkg -> _._1)).toSet
    val references = rawReferences(files, ownership, rootPackage).groupBy(_.loc.path)
    outside.map { file =>
      val imported                                 = references.getOrElse(file.source.projectPath, Nil)
      def importedAs(symbol: String, name: String) = imported.collectFirst {
        case ref if ref.symbol == symbol && defined(ref.targetPath -> name) => ref.targetPath
      }
      def resolve(name: String): Option[OutsideImport] =
        Option.when(defined(file.pkg -> name))(file.pkg).orElse(importedAs(name, name)).orElse(importedAs("*", name)).map(OutsideImport(_, name))
      file.source.packagePath -> Trees
        .topLevelDefinitions(file.tree)
        .groupMap(_._1)(_._2)
        .map((name, stats) => name -> stats.flatMap(parentNames).distinct.flatMap(resolve).filterNot(_ == OutsideImport(file.pkg, name)))
        .filter(_._2.nonEmpty)
    }.toMap
  }

  private def outsideFiles(files: List[ParsedFile], ownership: Ownership): List[ParsedFile] =
    files.filter(file =>
      ownership.ownerOf(file.pkg) match {
        case Owner.Module(_) => false
        case _               => true
      }
    )

  private def parentNames(stat: Stat): List[String] = stat match {
    case d: Defn.Class  => Trees.parentNames(d.templ)
    case d: Defn.Trait  => Trees.parentNames(d.templ)
    case d: Defn.Object => Trees.parentNames(d.templ)
    case d: Defn.Enum   => Trees.parentNames(d.templ)
    case d: Defn.Given  => Trees.parentNames(d.templ)
    case _              => Nil
  }

  private def rawReferences(
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  ): List[RawReference] = {
    val topPackages = files.map(_.pkg.split('/').head).filter(_.nonEmpty).toSet
    files.flatMap { file =>
      val from = ownership.ownerOf(file.pkg)
      imports(file, from, rootPackage, topPackages) ++ qualified(file, from, rootPackage)
    }
  }

  private def packagesOf(path: String, symbol: String): List[String] = {
    val segments = path.split('/').toList.filter(_.nonEmpty)
    val pkg      = segments.takeWhile(isPackageName)
    val named    = Option.when(pkg == segments && symbol != "*" && symbol != "given" && isPackageName(symbol))(pkg :+ symbol)
    (named.toList :+ pkg).map(_.mkString("/"))
  }

  private def isPackageName(segment: String): Boolean = !segment.headOption.exists(_.isUpper)

  private def isGiven(stat: Stat): Boolean = stat match {
    case _: Defn.Given | _: Defn.GivenAlias => true
    case member: Stat.WithMods              => member.mods.exists(_.is[Mod.Implicit])
    case _                                  => false
  }

  private def locate(
    moduleId: String,
    targetPath: String,
    symbol: String
  ): (String, String) = {
    val inside   = if (targetPath == moduleId) Nil else targetPath.stripPrefix(s"$moduleId/").split('/').toList
    val segments = inside :+ symbol
    segments.indexWhere(_.headOption.exists(_.isUpper)) match {
      case 0  => ("module", segments.head)
      case -1 => (inside.headOption.getOrElse("module"), symbol)
      case i  => (segments.head, segments(i))
    }
  }

  private def imports(
    file: ParsedFile,
    from: Owner,
    rootPackage: String,
    topPackages: Set[String]
  ): List[RawReference] =
    file.tree
      .collect {
        case importStat: Import => importStat.importers
        case exportStat: Export => exportStat.importers
      }
      .flatten
      .flatMap { importer =>
        val ref = importer.ref.syntax
        Packages
          .relative(rootPackage, ref)
          .orElse(Option.when(file.opensRoot && topPackages(ref.split('.').head))(ref.replace('.', '/')))
          .toList
          .flatMap { path =>
            importer.importees.flatMap(symbolOf).map(RawReference(from, path, _, Trees.loc(file, importer)))
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
        segments.lastOption.map(symbol => RawReference(from, segments.dropRight(1).mkString("/"), symbol, Trees.loc(file, select)))
      }

  private def outermostQualified(tree: Tree, rootPackage: String): Boolean =
    tree.syntax.startsWith(s"$rootPackage.") && !tree.parent.exists(parent => enclosesQualified(parent, tree, rootPackage)) &&
      !Trees.ancestors(tree).exists {
        case _: Import | _: Export => true
        case _                     => false
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

  private def symbolOf(importee: Importee): Option[String] = importee match {
    case Importee.Name(name)      => Some(name.value)
    case Importee.Rename(name, _) => Some(name.value)
    case _: Importee.Wildcard     => Some("*")
    case _: Importee.Given        => Some("given")
    case _: Importee.GivenAll     => Some("given")
    case _                        => None
  }
}
