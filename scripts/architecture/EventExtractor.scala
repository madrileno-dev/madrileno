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
    val declared    = files.flatMap(topicsIn(_, ownership))
    val topics      = declared.collect { case Right(topic) => topic }
    val busUses     = files.flatMap(busUsesIn(_, ownership))
    val found       = files.flatMap(descriptorsIn(_, ownership))
    val descriptors = found.collect { case Right(event) => event }
    val unnamed     = found.collect { case Left(event) => event }
    val publishes   = files.flatMap(publishesIn(_, ownership))
    val subscribed  = files.flatMap(subscribesIn(_, ownership))
    val subscribes  = subscribed.collect { case Right(subscription) => subscription }

    val namesByType  = topics.groupMap(_.eventType)(_.name).view.mapValues(_.distinct.sorted).toMap
    val linkedTopics = topics.map { topic =>
      val uses = if (namesByType(topic.eventType).size > 1) Nil else busUses.filter(_.eventType == topic.eventType)
      topic.copy(publishers = uses.filter(_.role == Role.Publishes).map(_.site), subscribers = uses.filter(_.role == Role.Subscribes).map(_.site))
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
        publishers = resolvedPublishes.filter(_._1 == event.typeName).map(_._2),
        subscribers = subscribes.filter(_._1 == event.typeName).map(_._2)
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
      topicsByName.map(same => same.head.copy(publishers = same.flatMap(_.publishers).distinct, subscribers = same.flatMap(_.subscribers).distinct))
    val eventsByType = byDeclaration(linkedEvents)(_.eventType)(_.loc)
    val events       =
      eventsByType.map(same => same.head.copy(publishers = same.flatMap(_.publishers).distinct, subscribers = same.flatMap(_.subscribers).distinct))
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

  private def busUsesIn(file: ParsedFile, ownership: Ownership): List[BusUse] = {
    val scopes = file.tree.collect {
      case cls: Defn.Class =>
        (cls: Tree) -> (
          cls.name.value,
          cls.ctor.paramClauses.flatMap(_.values).flatMap(p => busOf(p.name.value, p.decltpe)).toMap ++ busesIn(cls.templ)
        )
      case t: Defn.Trait    => (t: Tree)   -> (t.name.value, busesIn(t.templ))
      case obj: Defn.Object => (obj: Tree) -> (obj.name.value, busesIn(obj.templ))
    }
    file.tree.collect {
      case select @ Term.Select(receiver, Term.Name(method)) if busRole(method).isDefined =>
        busName(receiver)
          .flatMap(name =>
            Trees
              .ancestors(select)
              .flatMap(ancestor => scopes.find(_._1 eq ancestor).map(_._2))
              .collectFirst { case (enclosing, buses) if buses.contains(name) => (enclosing, buses(name)) }
          )
          .map((enclosing, eventType) =>
            BusUse(
              eventType,
              busRole(method).getOrElse(Role.Subscribes),
              Site(ownership.ownerOf(file.pkg), enclosing, Trees.loc(file, select)),
              file.source.packagePath
            )
          )
    }.flatten
  }

  private def busName(receiver: Term): Option[String] = receiver match {
    case Term.Name(name)                            => Some(name)
    case Term.Select(_: Term.This, Term.Name(name)) => Some(name)
    case _                                          => None
  }

  private def busesIn(template: Template): Map[String, String] =
    Trees
      .members(template)
      .flatMap {
        case d: Defn.Val => Trees.patNames(d.pats).flatMap(name => busOf(name, d.decltpe).orElse(topicTypeOf(d.rhs).map(name -> _)))
        case d: Decl.Val => Trees.patNames(d.pats).flatMap(busOf(_, Some(d.decltpe)))
        case d: Defn.Def => busOf(d.name.value, d.decltpe)
        case d: Decl.Def => busOf(d.name.value, Some(d.decltpe))
        case _           => Nil
      }
      .toMap

  private def busOf(name: String, tpe: Option[Type]): Option[(String, String)] = tpe.collect {
    case Type.Apply.After_4_6_0(Type.Name("EventBus"), targs) if targs.values.size == 1 => name -> targs.values.head.syntax
  }

  private def topicTypeOf(rhs: Term): Option[String] = rhs match {
    case Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("topic")), targs), _) if targs.values.size == 1 =>
      Some(targs.values.head.syntax)
    case _ => None
  }

  private def busRole(method: String): Option[Role] = method match {
    case "publish"                      => Some(Role.Publishes)
    case "subscribe" | "subscribeAwait" => Some(Role.Subscribes)
    case _                              => None
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
