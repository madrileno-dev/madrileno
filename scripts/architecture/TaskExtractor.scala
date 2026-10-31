package architecture

import scala.meta.{Member as _, *}

object TaskExtractor {
  private final case class Definition(
    task: TaskDecl,
    definedAs: Option[String],
    named: Boolean)

  private final case class Constant(
    path: String,
    obj: Option[String],
    name: String,
    value: String)

  def extract(files: List[ParsedFile], ownership: Ownership): Extracted[List[TaskDecl]] = {
    val constants   = files.flatMap(constantsIn)
    val found       = files.flatMap(file => definitionsIn(file, ownership, knownName(file, constants)))
    val definitions = found.collect { case Right(definition) => definition }
    val resolved    = files.flatMap(instancesIn(_, ownership)).flatMap((name, site) => resolve(definitions, name, site).map(_ -> site))
    val scheduled   = definitions.map { definition =>
      definition -> definition.task.copy(scheduledFrom =
        resolved.collect { case (`definition`, site) => site }.distinctBy(site => (site.owner, site.enclosing))
      )
    }
    val tasks      = scheduled.map(_._2)
    val duplicates = scheduled.collect { case (definition, task) if definition.named => task }.groupBy(_.name).values.toList.collect {
      case same if same.size > 1 =>
        val ordered = same.sortBy(t => (t.loc.path, t.loc.line))
        val labels  = ordered.map(_.owner.label)
        val owners  = labels.distinct.map(label => if (labels.count(_ == label) == 1) label else s"$label (${labels.count(_ == label)} times)")
        Warning(
          WarningKind.DuplicateTask,
          s"duplicate-task:${ordered.head.name}",
          s"task ${ordered.head.name} is declared in ${owners.mkString(", ")}",
          ordered(1).loc
        )
    }
    Extracted(tasks.sortBy(t => (t.name, t.owner.label)), duplicates.sortBy(_.key), found.collect { case Left(item) => item }.distinctBy(_.key))
  }

  private def resolve(
    definitions: List[Definition],
    name: String,
    site: Site
  ): Option[Definition] = {
    val candidates = definitions.filter(_.definedAs.contains(name))
    List(candidates.filter(_.task.owner == site.owner), candidates).collectFirst { case List(only) => only }
  }

  private def constantsIn(file: ParsedFile): List[Constant] =
    file.tree.collect { case d: Defn.Val => (d, Trees.patNames(d.pats), d.rhs) }.collect { case (d, List(name), Lit.String(value)) =>
      Constant(file.source.projectPath, objectOf(d), name, value)
    }

  private def objectOf(d: Defn.Val): Option[String] =
    Trees.ancestors(d).collectFirst { case o: Defn.Object => o }.filter(o => Trees.members(o.templ).exists(_ eq d)).map(_.name.value)

  private def definitionsIn(
    file: ParsedFile,
    ownership: Ownership,
    knownName: Term => Option[String]
  ): List[Either[Unclassified, Definition]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(fun, args) if taskMethod(fun).contains("recurring") =>
        Trees.argument(args.values, "name", 0).filterNot(isParameter).map { name =>
          Right(definition(file, ownership, call, name, knownName, TaskKind.Recurring, Trees.argument(args.values, "schedule", 1).map(scheduleOf)))
        }
      case call @ Term.Apply.After_4_6_0(fun, args) if taskMethod(fun).exists(Set("oneTime", "custom")) =>
        Trees.argument(args.values, "descriptor", 0).flatMap { descriptor =>
          fromDescriptor(file, ownership, call, descriptor, if (taskMethod(fun).contains("oneTime")) TaskKind.OneTime else TaskKind.Custom, knownName)
        }
      case call @ Term.Apply.After_4_6_0(fun, args) if constructed(fun).isDefined =>
        Trees
          .argument(args.values, "descriptor", 0)
          .flatMap(fromDescriptor(file, ownership, call, _, constructed(fun).getOrElse(TaskKind.OneTime), knownName))
    }.flatten

  private def taskMethod(fun: Term): Option[String] = fun match {
    case Term.ApplyType.After_4_6_0(inner, _)              => taskMethod(inner)
    case Term.Select(Term.Name("Task"), Term.Name(method)) => Some(method)
    case _                                                 => None
  }

  private def fromDescriptor(
    file: ParsedFile,
    ownership: Ownership,
    call: Term,
    descriptor: Term,
    kind: TaskKind,
    knownName: Term => Option[String]
  ): Option[Either[Unclassified, Definition]] =
    taskNameOf(descriptor) match {
      case Some(name)                      => Some(Right(definition(file, ownership, call, name, knownName, kind, None)))
      case None if isParameter(descriptor) => None
      case None                            =>
        val enclosing = Trees.enclosingName(file, call)
        Some(
          Left(
            Unclassified(
              s"task-descriptor:${file.source.packagePath}:$enclosing:${Trees.collapse(descriptor.syntax, 80)}",
              s"a task in $enclosing takes the descriptor ${Trees.collapse(descriptor.syntax, 80)}, not a TaskDescriptor(...) call",
              Trees.loc(file, call)
            )
          )
        )
    }

  private def isParameter(term: Term): Boolean = term match {
    case Term.Name(name) =>
      Trees.ancestors(term).exists {
        case d: Defn.Def      => d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).exists(_.name.value == name)
        case c: Defn.Class    => c.ctor.paramClauses.flatMap(_.values).exists(_.name.value == name)
        case f: Term.Function => f.paramClause.values.exists(_.name.value == name)
        case _                => false
      }
    case _ => false
  }

  private def definition(
    file: ParsedFile,
    ownership: Ownership,
    call: Term,
    name: Term,
    knownName: Term => Option[String],
    kind: TaskKind,
    schedule: Option[String]
  ): Definition = {
    val definedAs = Trees.ancestors(call).collectFirst {
      case d: Defn.Val if Trees.patNames(d.pats).nonEmpty => Trees.patNames(d.pats).head
      case d: Defn.Def                                    => d.name.value
    }
    val known = knownName(name)
    Definition(
      TaskDecl(known.getOrElse(Trees.collapse(name.pos.text, 80)), kind, schedule, ownership.ownerOf(file.pkg), Nil, Trees.loc(file, call)),
      definedAs,
      known.isDefined
    )
  }

  private def constructed(fun: Term): Option[TaskKind] = fun match {
    case Term.ApplyType.After_4_6_0(inner, _) => constructed(inner)
    case Term.Name("OneTimeTask")             => Some(TaskKind.OneTime)
    case Term.Name("CustomTask")              => Some(TaskKind.Custom)
    case _                                    => None
  }

  private def taskNameOf(term: Term): Option[Term] = term match {
    case Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Name("TaskDescriptor"), _), args) => Trees.argument(args.values, "taskName", 0)
    case Term.Apply.After_4_6_0(Term.Name("TaskDescriptor"), args)                                => Trees.argument(args.values, "taskName", 0)
    case _                                                                                        => None
  }

  private def knownName(file: ParsedFile, constants: List[Constant])(term: Term): Option[String] =
    Trees.stringLiteral(term).orElse(constantOf(file, constants, term))

  private def constantOf(
    file: ParsedFile,
    constants: List[Constant],
    term: Term
  ): Option[String] = {
    val candidates = term match {
      case Term.Name(name) if !isParameter(term) => constants.filter(c => c.name == name && c.path == file.source.projectPath)
      case Term.Select(qualifier, Term.Name(name)) if !isParameter(root(qualifier)) =>
        constants.filter(c => c.name == name && c.obj.exists(lastName(qualifier).contains))
      case _ => Nil
    }
    List(candidates.filter(_.path == file.source.projectPath), candidates).map(_.map(_.value).distinct).collectFirst { case List(only) => only }
  }

  private def root(term: Term): Term = term match {
    case Term.Select(qualifier, _) => root(qualifier)
    case other                     => other
  }

  private def scheduleOf(term: Term): String = term match {
    case Term.Apply.After_4_6_0(fun, args) if lastName(fun).contains("Cron") =>
      Trees
        .argument(args.values, "expression", 0)
        .flatMap(_.collect { case lit: Lit.String => lit.value }.headOption)
        .fold(Trees.collapse(term.syntax, 80))(cron => s"cron $cron")
    case Term.Apply.After_4_6_0(fun, args) if lastName(fun).contains("RecurringWithFixedRate") && Trees.argument(args.values, "every", 0).isDefined =>
      s"every ${Trees.argument(args.values, "every", 0).map(_.syntax).getOrElse("")}"
    case Term.Apply.After_4_6_0(fun, args)
        if lastName(fun).contains("RecurringWithFixedDelay") && Trees.argument(args.values, "after", 0).isDefined =>
      s"after ${Trees.argument(args.values, "after", 0).map(_.syntax).getOrElse("")}"
    case other => Trees.collapse(other.syntax, 80)
  }

  private def lastName(term: Term): Option[String] = term match {
    case Term.Select(_, Term.Name(name)) => Some(name)
    case Term.Name(name)                 => Some(name)
    case _                               => None
  }

  private def instancesIn(file: ParsedFile, ownership: Ownership): List[(String, Site)] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.Select(receiver, Term.Name("instance")), _) if lastName(receiver).isDefined =>
        lastName(receiver).toList.map(_ -> Site(ownership.ownerOf(file.pkg), Trees.enclosingName(file, call), Trees.loc(file, call)))
    }.flatten
}
