package architecture

import scala.meta.{Member as _, *}

object EventExtractor {
  private enum Role {
    case Publishes, Subscribes
  }

  private final case class BusUse(
    eventType: String,
    role: Role,
    site: Site,
    packagePath: String)

  def extract(files: List[ParsedFile], ownership: Ownership): Extracted[(List[BusTopic], List[OutboxEvent])] = {
    val declared = files.flatMap(topicsIn(_, ownership))
    val topics   = declared.collect { case Right(topic) => topic }
    val busTypes = files
      .flatMap(_.tree.collect { case scope @ (_: Defn.Class | _: Defn.Trait | _: Defn.Object) => bindings(scope) })
      .flatMap(_.collect { case (name, Some(eventType)) => name -> eventType })
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct)
      .toMap
    val busUses     = files.flatMap(busUsesIn(_, busTypes, ownership))
    val found       = files.flatMap(descriptorsIn(_, ownership))
    val descriptors = found.collect { case Right(event) => event }
    val unnamed     = found.collect { case Left(event) => event }
    val publishes   = files.flatMap(publishesIn(_, ownership))
    val subscribed  = files.flatMap(subscribesIn(_, ownership))
    val subscribes  = subscribed.collect { case Right(subscription) => subscription }

    val namesByType  = topics.groupMap(_.eventType)(_.name).view.mapValues(_.distinct.sorted).toMap
    val linkedTopics = topics.map { topic =>
      val uses = if (namesByType(topic.eventType).size > 1) Nil else busUses.filter(_.eventType == topic.eventType)
      topic.copy(
        publishers = uses.filter(_.role == Role.Publishes).map(_.site).distinctBy(perClass),
        subscribers = uses.filter(_.role == Role.Subscribes).map(_.site).distinctBy(perClass)
      )
    }
    val ambiguousBus = busUses
      .flatMap(use =>
        namesByType.get(use.eventType).filter(_.size > 1).map { names =>
          Unclassified(
            s"bus-ambiguous:${use.packagePath}:${use.eventType}",
            s"EventBus[${use.eventType}] in ${use.site.enclosing} could be topic ${names.mkString(" or ")}",
            use.site.loc
          )
        }
      )
      .distinctBy(_.key)
    val busWithoutTopic = busUses
      .filterNot(use => topics.exists(_.eventType == use.eventType))
      .map(use =>
        Unclassified(
          s"bus-without-topic:${use.packagePath}:${use.eventType}",
          s"EventBus[${use.eventType}] in ${use.site.enclosing} has no topic declaration",
          use.site.loc
        )
      )
      .distinctBy(_.key)

    val resolvedPublishes = publishes.collect { case Right(published) => published }
    val linkedEvents      = descriptors.map { event =>
      event.copy(
        publishers = resolvedPublishes.filter(_._1 == event.typeName).map(_._2).distinctBy(perClass),
        subscribers = subscribes.filter(_._1 == event.typeName).map(_._2).distinctBy(s => (s.consumer, perClass(s.site)))
      )
    }
    val unknownTypes =
      (resolvedPublishes.map((typeName, site) => typeName -> site.loc) ++ subscribes.map((typeName, sub) => typeName -> sub.site.loc))
        .filterNot((typeName, _) => descriptors.exists(_.typeName == typeName) || unnamed.exists(_.typeName == typeName))
        .map((typeName, loc) =>
          Unclassified(s"outbox-event-unknown:$typeName", s"$typeName is published or subscribed but has no DomainEventDescriptor", loc)
        )
        .distinctBy(_.key)

    val topicsByName = byDeclaration(linkedTopics)(_.name)(_.loc)
    val mergedTopics =
      topicsByName.map(same =>
        same.head.copy(publishers = same.flatMap(_.publishers).distinctBy(perClass), subscribers = same.flatMap(_.subscribers).distinctBy(perClass))
      )
    val eventsByType = byDeclaration(linkedEvents)(_.eventType)(_.loc)
    val events       =
      eventsByType.map(same =>
        same.head.copy(
          publishers = same.flatMap(_.publishers).distinctBy(perClass),
          subscribers = same.flatMap(_.subscribers).distinctBy(s => (s.consumer, perClass(s.site)))
        )
      )
    val duplicates = topicsByName.collect { case same @ (first :: second :: _) =>
      Warning(
        WarningKind.DuplicateTopic,
        s"duplicate-topic:${first.name}",
        s"topic ${first.name} is declared by ${same.map(t => s"topic[${t.eventType}] in ${t.owner.label}").mkString(", ")}",
        second.loc
      )
    } ++ eventsByType.collect { case same @ (first :: second :: _) =>
      Warning(
        WarningKind.DuplicateEvent,
        s"duplicate-event:${first.eventType}",
        s"event ${first.eventType} is declared by ${same.map(e => s"${e.typeName} in ${e.owner.label}").mkString(", ")}",
        second.loc
      )
    }

    val warnings =
      mergedTopics.collect {
        case topic if topic.subscribers.nonEmpty && topic.publishers.isEmpty =>
          Warning(
            WarningKind.SubscriberWithoutPublisher,
            s"no-publisher:topic:${topic.name}",
            s"topic ${topic.name} has subscribers but no publisher",
            topic.loc
          )
      } ++ events.collect {
        case event if event.subscribers.nonEmpty && event.publishers.isEmpty =>
          Warning(
            WarningKind.SubscriberWithoutPublisher,
            s"no-publisher:event:${event.eventType}",
            s"${event.typeName} (${event.eventType}) has subscribers but no publisher",
            event.loc
          )
      } ++ duplicates

    val types = typeDefinitions(files, ownership)

    Extracted(
      (
        mergedTopics.map(topic => topic.copy(definedIn = fileOf(types, topic.eventType, topic.loc.path, topic.owner))).sortBy(_.name),
        events
          .map(event =>
            event.copy(definedIn =
              fileOf(types, event.typeName, event.loc.path, event.owner)
                .orElse(files.find(_.source.projectPath == event.loc.path).map(_.source.packagePath))
            )
          )
          .sortBy(_.eventType)
      ),
      warnings,
      declared.collect { case Left(item) => item } ++ busWithoutTopic ++ ambiguousBus ++ publishes.collect { case Left(item) =>
        item
      } ++ unknownTypes ++
        unnamed.map(_.item) ++ subscribed.collect { case Left(item) => item }
    )
  }

  private final case class TypeDefinition(
    name: String,
    path: String,
    file: String,
    owner: Owner)

  private def typeDefinitions(files: List[ParsedFile], ownership: Ownership): List[TypeDefinition] =
    files.flatMap { file =>
      file.tree
        .collect {
          case d: Defn.Class => d.name.value
          case d: Defn.Trait => d.name.value
          case d: Defn.Enum  => d.name.value
        }
        .map(TypeDefinition(_, file.source.projectPath, file.source.packagePath, ownership.ownerOf(file.pkg)))
    }

  private def fileOf(
    types: List[TypeDefinition],
    typeName: String,
    declaredIn: String,
    owner: Owner
  ): Option[String] = {
    val candidates = types.filter(_.name == typeName.takeWhile(_ != '[').split('.').last)
    List(candidates.filter(_.path == declaredIn), candidates.filter(_.owner == owner), candidates).collectFirst { case List(only) => only.file }
  }

  private def byDeclaration[A](items: List[A])(key: A => String)(loc: A => Loc): List[List[A]] =
    items.groupBy(key).values.toList.map(_.sortBy(item => (loc(item).path, loc(item).line)))

  private def topicsIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, BusTopic]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("topic")), targs), args)
          if targs.values.size == 1 && args.values.nonEmpty =>
        val eventType = targs.values.head.syntax
        val name      = Trees.argument(args.values, "name", 0).getOrElse(args.values.head)
        Trees
          .stringLiteral(name)
          .map(name => BusTopic(name, eventType, ownership.ownerOf(file.pkg), Nil, Nil, Trees.loc(file, call)))
          .toRight(
            Unclassified(
              s"topic-name:${file.source.packagePath}:$eventType",
              s"topic[$eventType] in ${Trees.enclosingName(call)} is named by ${name.syntax}, not a string literal",
              Trees.loc(file, call)
            )
          )
    }

  private def busUsesIn(
    file: ParsedFile,
    busTypes: Map[String, List[String]],
    ownership: Ownership
  ): List[BusUse] =
    file.tree.collect { case select @ Term.Select(receiver, Term.Name(BusRole(role))) =>
      def declared(name: String) = Trees.ancestors(select).view.flatMap(bindings(_).get(name)).headOption
      def unique(name: String)   = busTypes.get(name).collect { case List(eventType) => eventType }
      val eventType              = receiver match {
        case Term.Name(name)                            => declared(name).getOrElse(unique(name))
        case Term.Select(_: Term.This, Term.Name(name)) => declared(name).getOrElse(unique(name))
        case Term.Select(_, Term.Name(name))            => unique(name)
        case _                                          => None
      }
      eventType.map(BusUse(_, role, Site(ownership.ownerOf(file.pkg), Trees.enclosingName(select), Trees.loc(file, select)), file.source.packagePath))
    }.flatten

  private def bindings(tree: Tree): Map[String, Option[String]] = {
    val generic                          = typeParams(tree)
    def params(values: List[Term.Param]) = values.map(p => p.name.value -> busType(p.decltpe, generic)).toMap
    tree match {
      case cls: Defn.Class  => params(cls.ctor.paramClauses.flatMap(_.values).toList) ++ declarations(Trees.members(cls.templ), generic)
      case t: Defn.Trait    => declarations(Trees.members(t.templ), generic)
      case obj: Defn.Object => declarations(Trees.members(obj.templ), generic)
      case d: Defn.Def      => params(d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values))
      case f: Term.Function => params(f.paramClause.values)
      case b: Term.Block    => declarations(b.stats, generic)
      case f @ (_: Term.For | _: Term.ForYield) =>
        f.collect {
          case e: Enumerator.Generator     => e.pat
          case e: Enumerator.CaseGenerator => e.pat
          case e: Enumerator.Val           => e.pat
        }.flatMap(patternNames(_, generic))
          .toMap
      case c: Case => patternNames(c.pat, generic).toMap
      case _       => Map.empty
    }
  }

  private def declarations(stats: List[Stat], generic: Set[String]): Map[String, Option[String]] =
    stats.flatMap {
      case d: Defn.Val => Trees.patNames(d.pats).map(_ -> busType(d.decltpe, generic).orElse(topicTypeOf(d.rhs)))
      case d: Decl.Val => Trees.patNames(d.pats).map(_ -> busType(Some(d.decltpe), generic))
      case d: Defn.Def => List(d.name.value -> busType(d.decltpe, generic))
      case d: Decl.Def => List(d.name.value -> busType(Some(d.decltpe), generic))
      case _           => Nil
    }.toMap

  private def patternNames(pat: Pat, generic: Set[String]): List[(String, Option[String])] =
    pat.collect { case Pat.Var(name) => name.value -> None } ++ pat.collect { case Pat.Typed(Pat.Var(name), tpe) =>
      name.value -> busType(Some(tpe), generic)
    }

  private def perClass(site: Site): (Owner, String) = (site.owner, site.enclosing)

  private def typeParams(tree: Tree): Set[String] =
    (tree :: Trees.ancestors(tree))
      .flatMap {
        case c: Defn.Class => c.tparamClause.values
        case t: Defn.Trait => t.tparamClause.values
        case d: Defn.Def   => d.paramClauseGroups.flatMap(_.tparamClause.values)
        case _             => Nil
      }
      .map(_.name.value)
      .toSet

  private def busType(tpe: Option[Type], generic: Set[String]): Option[String] =
    tpe
      .collect {
        case Type.Apply.After_4_6_0(Type.Name("EventBus"), targs) if targs.values.size == 1 => targs.values.head.syntax
      }
      .filterNot(generic)

  private def topicTypeOf(rhs: Term): Option[String] = rhs match {
    case Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("topic")), targs), _) if targs.values.size == 1 =>
      Some(targs.values.head.syntax)
    case _ => None
  }

  private object BusRole {
    def unapply(method: String): Option[Role] = method match {
      case "publish"                      => Some(Role.Publishes)
      case "subscribe" | "subscribeAwait" => Some(Role.Subscribes)
      case _                              => None
    }
  }

  private final case class UnnamedEvent(typeName: String, item: Unclassified)

  private def descriptorsIn(file: ParsedFile, ownership: Ownership): List[Either[UnnamedEvent, OutboxEvent]] =
    file.tree.collect {
      case alias: Defn.GivenAlias if Trees.typeName(alias.decltpe) == "DomainEventDescriptor" =>
        (alias.decltpe, alias.body) match {
          case (Type.Apply.After_4_6_0(_, targs), Term.Apply.After_4_6_0(fun, args)) if isDescriptor(fun) =>
            val typeName = targs.values.head.syntax
            Trees.argument(args.values, "eventType", 0).map { eventType =>
              Trees
                .stringLiteral(eventType)
                .map(OutboxEvent(typeName, _, ownership.ownerOf(file.pkg), Nil, Nil, Trees.loc(file, alias)))
                .toRight(
                  UnnamedEvent(
                    typeName,
                    Unclassified(
                      s"outbox-event-name:${file.source.packagePath}:$typeName",
                      s"DomainEventDescriptor[$typeName] in ${Trees.enclosingName(alias)} has the event type ${eventType.syntax}, not a string literal",
                      Trees.loc(file, alias)
                    )
                  )
                )
            }
          case _ => None
        }
    }.flatten

  private def isDescriptor(fun: Term): Boolean = fun match {
    case Term.ApplyType.After_4_6_0(inner, _) => isDescriptor(inner)
    case other                                => other.syntax.endsWith("DomainEventDescriptor")
  }

  private def publishesIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, (String, Site)]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(fun, args) if publishedType(fun).isDefined && args.values.size == 1 =>
        val site = Site(ownership.ownerOf(file.pkg), Trees.enclosingName(call), Trees.loc(file, call))
        publishedType(fun).flatten
          .orElse(eventTypeOf(args.values.head))
          .map(_ -> site)
          .toRight(
            Unclassified(
              s"outbox-publish:${file.source.packagePath}:${site.enclosing}:${Trees.collapse(args.values.head.syntax, 80)}",
              s"publishTransactionally argument ${args.values.head.syntax} has no visible event type",
              site.loc
            )
          )
    }

  private def publishedType(fun: Term): Option[Option[String]] = fun match {
    case Term.Select(_, Term.Name("publishTransactionally"))                                                              => Some(None)
    case Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("publishTransactionally")), targs) if targs.values.size == 1 =>
      Some(Some(Trees.typeName(targs.values.head)))
    case _ => None
  }

  private def eventTypeOf(arg: Term): Option[String] = arg match {
    case Term.Apply.After_4_6_0(Term.Name(name), _) if isTypeName(name)                 => Some(name)
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(name), _), _) if isTypeName(name) => Some(name)
    case Term.Select(Term.Name(name), _) if isTypeName(name)                            => Some(name)
    case _                                                                              => None
  }

  private def isTypeName(name: String): Boolean = name.headOption.exists(_.isUpper)

  private def subscribesIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, (String, OutboxSubscriber)]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(fun, targs), args)
          if Set("OutboxSubscription", "OutboxSubscription.withDelivery").contains(fun.syntax) && targs.values.size == 1 =>
        val typeName = targs.values.head.syntax
        val site     = Site(ownership.ownerOf(file.pkg), Trees.enclosingName(call), Trees.loc(file, call))
        Trees.argument(args.values, "consumer", 0).map { consumer =>
          Trees
            .stringLiteral(consumer)
            .map(name => typeName -> OutboxSubscriber(name, site))
            .toRight(
              Unclassified(
                s"outbox-subscription:${file.source.packagePath}:$typeName",
                s"OutboxSubscription[$typeName] in ${site.enclosing} names its consumer by ${consumer.syntax}, not a string literal",
                site.loc
              )
            )
        }
    }.flatten
}
