package architecture

import scala.meta.{Member as _, *}

object ModuleExtractor {
  private val ItemLength = 80

  private val InfixOperators = Set(":+", "+:", "++", "~", "::")

  def extract(files: List[ParsedFile], ownership: Ownership): Extracted[List[Module]] = {
    val mixedIn = applicationLoaderParents(files, ownership)
    val results = files.flatMap { file =>
      Ownership.moduleTraits(file).map(module(file, _, files, ownership, mixedIn))
    }
    Extracted(results.map(_.value).sortBy(_.id), results.flatMap(_.warnings), results.flatMap(_.unclassified))
  }

  private def module(
    file: ParsedFile,
    defn: Defn.Trait,
    files: List[ParsedFile],
    ownership: Ownership,
    mixedIn: Set[String]
  ): Extracted[Module] = {
    val id         = file.pkg
    val stats      = Trees.members(defn.templ)
    val ownFiles   = files.filter(f => ownership.ownerOf(f.pkg) == Owner.Module(id))
    val components = stats.flatMap(componentOf(file, defn, id, ownFiles, _))
    val resolved   = components.collect { case Right(component) => component }
    Extracted(
      Module(
        id = id,
        traitName = defn.name.value,
        mixedIn = mixedIn.contains(defn.name.value),
        consumes = stats.flatMap(consumed(file, _)),
        provides = stats.flatMap(provided(file, _)),
        contributions = stats.flatMap(contribution(file, _)),
        components = resolved,
        metrics = Metrics(ownFiles.map(f => Trees.nonBlankLines(f.source.content)).sum, resolved.size, 0, 0),
        loc = loc(file, defn)
      ),
      Nil,
      components.collect { case Left(item) => item }
    )
  }

  private def consumed(file: ParsedFile, stat: Stat): List[Member] = stat match {
    case d: Decl.Val if !Trees.isOverride(d.mods)   => Trees.patNames(d.pats).map(Member(_, d.decltpe.syntax, loc(file, d)))
    case d: Decl.Given if !Trees.isOverride(d.mods) => List(Member(d.name.value, d.decltpe.syntax, loc(file, d)))
    case d: Decl.Def if !Trees.isOverride(d.mods)   => List(Member(d.name.value, d.decltpe.syntax, loc(file, d)))
    case _                                          => Nil
  }

  private def provided(file: ParsedFile, stat: Stat): List[Member] = stat match {
    case d: Defn.Val if visible(d.mods) =>
      Trees.patNames(d.pats).map(Member(_, d.decltpe.map(_.syntax).orElse(instantiated(d.rhs)).getOrElse(""), loc(file, d)))
    case d: Defn.GivenAlias if visible(d.mods) => List(Member(d.name.value, d.decltpe.syntax, loc(file, d)))
    case _                                     => Nil
  }

  private def visible(mods: List[Mod]): Boolean = !Trees.isPrivate(mods) && !Trees.isOverride(mods)

  private def instantiated(rhs: Term): Option[String] = rhs match {
    case Term.ApplyType.After_4_6_0(Term.Name("wire"), targs) => targs.values.headOption.map(Trees.typeName)
    case Term.New(init)                                       => Some(Trees.typeName(init.tpe))
    case _                                                    => None
  }

  private def contribution(file: ParsedFile, stat: Stat): Option[Contribution] = stat match {
    case d: Defn.Def if Trees.isAbstractOverride(d.mods) =>
      registry(d.name.value, d.paramClauseGroups.flatMap(_.paramClauses.flatMap(_.values)).size).map { registry =>
        Contribution(registry, operands(lastExpression(d.body)).map(t => Trees.collapse(t.syntax, ItemLength)), loc(file, d))
      }
    case _ => None
  }

  private def registry(name: String, arity: Int): Option[Registry] = (name, arity) match {
    case ("route", 0)               => Some(Registry.Routes)
    case ("route", 1)               => Some(Registry.AuthRoutes)
    case ("wsRoutes", 1)            => Some(Registry.WsRoutes)
    case ("wsRoutes", 2)            => Some(Registry.AuthWsRoutes)
    case ("recurringTasks", _)      => Some(Registry.RecurringTasks)
    case ("oneTimeTasks", _)        => Some(Registry.OneTimeTasks)
    case ("customTasks", _)         => Some(Registry.CustomTasks)
    case ("outboxSubscriptions", _) => Some(Registry.OutboxSubscriptions)
    case ("mailPreviews", _)        => Some(Registry.MailPreviews)
    case ("lifecycles", _)          => Some(Registry.Lifecycles)
    case _                          => None
  }

  private def lastExpression(term: Term): Term = term match {
    case Term.Block(stats) =>
      stats.lastOption match {
        case Some(last: Term) => lastExpression(last)
        case _                => term
      }
    case other => other
  }

  private def operands(term: Term): List[Term] = term match {
    case infix: Term.ApplyInfix if InfixOperators.contains(infix.op.value) =>
      operands(infix.lhs) ++ infix.argClause.values.flatMap(operands)
    case other if other.syntax.startsWith("super.") => Nil
    case other                                      => List(other)
  }

  private def componentOf(
    file: ParsedFile,
    defn: Defn.Trait,
    moduleId: String,
    ownFiles: List[ParsedFile],
    stat: Stat
  ): Option[Either[Unclassified, Component]] = stat match {
    case d: Defn.Val =>
      d.rhs match {
        case Term.ApplyType.After_4_6_0(Term.Name("wire"), targs) =>
          targs.values.headOption.map(Trees.typeName).map { name =>
            component(name, ownFiles).toRight(
              Unclassified(s"component:$moduleId:$name", s"wire[$name] in ${defn.name.value} has no class $name in module $moduleId", loc(file, d))
            )
          }
        case Term.New(init) => component(Trees.typeName(init.tpe), ownFiles).map(Right(_))
        case _              => None
      }
    case _ => None
  }

  private def component(name: String, ownFiles: List[ParsedFile]): Option[Component] =
    ownFiles.view.flatMap { file =>
      file.tree.collect { case cls: Defn.Class if cls.name.value == name => cls }.map { cls =>
        Component(name, kindOf(file.pkg), dependencies(cls), loc(file, cls))
      }
    }.headOption

  private def dependencies(cls: Defn.Class): List[String] =
    cls.ctor.paramClauses
      .filterNot(_.mod.exists {
        case _: Mod.Using => true
        case _            => false
      })
      .flatMap(_.values.map(_.decltpe.map(_.syntax).getOrElse("")))
      .toList

  private def kindOf(pkg: String): ComponentKind = pkg.split('/').lastOption match {
    case Some("services")     => ComponentKind.Service
    case Some("repositories") => ComponentKind.Repository
    case Some("routers")      => ComponentKind.Router
    case Some("gateways")     => ComponentKind.Gateway
    case _                    => ComponentKind.Other
  }

  private def applicationLoaderParents(files: List[ParsedFile], ownership: Ownership): Set[String] =
    files
      .filter(file => ownership.ownerOf(file.pkg) == Owner.CompositionRoot)
      .flatMap(file =>
        Trees
          .topLevel(file.tree)
          .collect {
            case d: Defn.Class if d.name.value == "ApplicationLoader"  => Trees.parentNames(d.templ)
            case d: Defn.Trait if d.name.value == "ApplicationLoader"  => Trees.parentNames(d.templ)
            case d: Defn.Object if d.name.value == "ApplicationLoader" => Trees.parentNames(d.templ)
          }
          .flatten
      )
      .toSet

  private def loc(file: ParsedFile, tree: Tree): Loc = Loc(file.source.projectPath, Parsing.lineOf(tree))
}
