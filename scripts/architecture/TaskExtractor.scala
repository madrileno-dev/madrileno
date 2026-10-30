package architecture

import scala.meta.{Member as _, *}

object TaskExtractor {
  private final case class Definition(task: TaskDecl, definedAs: Option[String])

  def extract(files: List[ParsedFile], ownership: Ownership): Extracted[List[TaskDecl]] = {
    val definitions = files.flatMap(definitionsIn(_, ownership))
    val resolved    = files.flatMap(instancesIn(_, ownership)).flatMap((name, site) => resolve(definitions, name, site).map(_ -> site))
    val tasks       = definitions.map { definition =>
      definition.task.copy(scheduledFrom = resolved.collect { case (`definition`, site) => site }.distinct)
    }
    val duplicates = tasks.groupBy(_.name).values.toList.collect {
      case same if same.size > 1 =>
        val ordered = same.sortBy(t => (t.loc.path, t.loc.line))
        Warning(
          WarningKind.DuplicateTask,
          s"duplicate-task:${ordered.head.name}",
          s"task ${ordered.head.name} is declared in ${ordered.map(_.owner.label).mkString(", ")}",
          ordered(1).loc
        )
    }
    Extracted(tasks.sortBy(t => (t.name, t.owner.label)), duplicates.sortBy(_.key), Nil)
  }

  private def resolve(
    definitions: List[Definition],
    name: String,
    site: Site
  ): Option[Definition] = {
    val candidates = definitions.filter(_.definedAs.contains(name))
    List(candidates.filter(_.task.owner == site.owner), candidates).collectFirst { case List(only) => only }
  }

  private def definitionsIn(file: ParsedFile, ownership: Ownership): List[Definition] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Task"), Term.Name("recurring")), args) =>
        Trees.argument(args.values, "name", 0).map { name =>
          definition(file, ownership, call, nameOf(name), TaskKind.Recurring, Trees.argument(args.values, "schedule", 1).map(scheduleOf))
        }
      case call @ Term.Apply.After_4_6_0(Term.Select(Term.Name("Task"), Term.Name(method @ ("oneTime" | "custom"))), args) =>
        Trees.argument(args.values, "descriptor", 0).flatMap(descriptorName).map { name =>
          definition(file, ownership, call, name, if (method == "oneTime") TaskKind.OneTime else TaskKind.Custom, None)
        }
      case call @ Term.Apply.After_4_6_0(fun, args) if constructed(fun).isDefined =>
        Trees
          .argument(args.values, "descriptor", 0)
          .flatMap(descriptorName)
          .map(name => definition(file, ownership, call, name, constructed(fun).getOrElse(TaskKind.OneTime), None))
    }.flatten

  private def definition(
    file: ParsedFile,
    ownership: Ownership,
    call: Term,
    name: String,
    kind: TaskKind,
    schedule: Option[String]
  ): Definition = {
    val definedAs = Trees.ancestors(call).collectFirst {
      case d: Defn.Val if Trees.patNames(d.pats).nonEmpty => Trees.patNames(d.pats).head
      case d: Defn.Def                                    => d.name.value
    }
    Definition(TaskDecl(name, kind, schedule, ownership.ownerOf(file.pkg), Nil, Trees.loc(file, call)), definedAs)
  }

  private def constructed(fun: Term): Option[TaskKind] = fun match {
    case Term.ApplyType.After_4_6_0(inner, _) => constructed(inner)
    case Term.Name("OneTimeTask")             => Some(TaskKind.OneTime)
    case Term.Name("CustomTask")              => Some(TaskKind.Custom)
    case _                                    => None
  }

  private def descriptorName(term: Term): Option[String] = term match {
    case Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Name("TaskDescriptor"), _), args) =>
      Trees.argument(args.values, "taskName", 0).map(nameOf)
    case Term.Apply.After_4_6_0(Term.Name("TaskDescriptor"), args) => Trees.argument(args.values, "taskName", 0).map(nameOf)
    case _                                                         => None
  }

  private def nameOf(term: Term): String = Trees.stringLiteral(term).getOrElse(Trees.collapse(term.syntax, 80))

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
        lastName(receiver).toList.map(_ -> Site(ownership.ownerOf(file.pkg), Trees.enclosingName(call), Trees.loc(file, call)))
    }.flatten
}
