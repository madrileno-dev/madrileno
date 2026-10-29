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
    val descriptors = files.flatMap(descriptorsIn(_, ownership))
    val publishes   = files.flatMap(publishesIn(_, ownership))
    val subscribes  = files.flatMap(subscribesIn(_, ownership))

    val linkedTopics = topics.map { topic =>
      val uses = busUses.filter(_.eventType == topic.eventType)
      topic.copy(publishers = uses.filter(_.role == Role.Publishes).map(_.site), subscribers = uses.filter(_.role == Role.Subscribes).map(_.site))
    }
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
    val events            = descriptors.map { event =>
      event.copy(
        publishers = resolvedPublishes.filter(_._1 == event.typeName).map(_._2),
        subscribers = subscribes.filter(_._1 == event.typeName).map(_._2)
      )
    }
    val unknownTypes =
      (resolvedPublishes.map((typeName, site) => typeName -> site.loc) ++ subscribes.map((typeName, sub) => typeName -> sub.site.loc))
        .filterNot((typeName, _) => descriptors.exists(_.typeName == typeName))
        .map((typeName, loc) =>
          Unclassified(s"outbox-event-unknown:$typeName", s"$typeName is published or subscribed but has no DomainEventDescriptor", loc)
        )
        .distinctBy(_.key)

    val warnings =
      linkedTopics.collect {
        case topic if topic.subscribers.nonEmpty && topic.publishers.isEmpty =>
          Warning(
            WarningKind.SubscriberWithoutPublisher,
            s"no-publisher:${topic.name}",
            s"topic ${topic.name} has subscribers but no publisher",
            topic.loc
          )
      } ++ events.collect {
        case event if event.subscribers.nonEmpty && event.publishers.isEmpty =>
          Warning(
            WarningKind.SubscriberWithoutPublisher,
            s"no-publisher:${event.eventType}",
            s"${event.typeName} (${event.eventType}) has subscribers but no publisher",
            event.loc
          )
      }

    Extracted(
      (linkedTopics.sortBy(_.name), events.sortBy(_.eventType)),
      warnings,
      declared.collect { case Left(item) => item } ++ busWithoutTopic ++ publishes.collect { case Left(item) => item } ++ unknownTypes
    )
  }

  private def topicsIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, BusTopic]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("topic")), targs), args)
          if targs.values.size == 1 && args.values.nonEmpty =>
        val eventType = targs.values.head.syntax
        Trees
          .stringLiteral(args.values.head)
          .map(name => BusTopic(name, eventType, ownership.ownerOf(file.pkg), Nil, Nil, Trees.loc(file, call)))
          .toRight(
            Unclassified(
              s"topic-name:${file.source.packagePath}:$eventType",
              s"topic[$eventType] in ${Trees.enclosingName(call)} is named by ${args.values.head.syntax}, not a string literal",
              Trees.loc(file, call)
            )
          )
    }

  private def busUsesIn(file: ParsedFile, ownership: Ownership): List[BusUse] =
    file.tree.collect { case cls: Defn.Class => cls }.flatMap { cls =>
      val buses = cls.ctor.paramClauses
        .flatMap(_.values)
        .flatMap { param =>
          param.decltpe.collect {
            case Type.Apply.After_4_6_0(Type.Name("EventBus"), targs) if targs.values.size == 1 =>
              param.name.value -> targs.values.head.syntax
          }
        }
        .toMap
      cls.templ.collect {
        case select @ Term.Select(Term.Name(name), Term.Name(method)) if buses.contains(name) && busRole(method).isDefined =>
          BusUse(
            buses(name),
            busRole(method).getOrElse(Role.Subscribes),
            Site(ownership.ownerOf(file.pkg), cls.name.value, Trees.loc(file, select)),
            file.source.packagePath
          )
      }
    }

  private def busRole(method: String): Option[Role] = method match {
    case "publish"                      => Some(Role.Publishes)
    case "subscribe" | "subscribeAwait" => Some(Role.Subscribes)
    case _                              => None
  }

  private def descriptorsIn(file: ParsedFile, ownership: Ownership): List[OutboxEvent] =
    file.tree.collect {
      case alias: Defn.GivenAlias if Trees.typeName(alias.decltpe) == "DomainEventDescriptor" =>
        (alias.decltpe, alias.body) match {
          case (Type.Apply.After_4_6_0(_, targs), Term.Apply.After_4_6_0(fun, args)) if fun.syntax.endsWith("DomainEventDescriptor") =>
            args.values.headOption
              .flatMap(Trees.stringLiteral)
              .map(OutboxEvent(targs.values.head.syntax, _, ownership.ownerOf(file.pkg), Nil, Nil, Trees.loc(file, alias)))
          case _ => None
        }
    }.flatten

  private def publishesIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, (String, Site)]] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name("publishTransactionally")), args) if args.values.size == 1 =>
        val site = Site(ownership.ownerOf(file.pkg), Trees.enclosingName(call), Trees.loc(file, call))
        eventTypeOf(args.values.head)
          .map(_ -> site)
          .toRight(
            Unclassified(
              s"outbox-publish:${file.source.packagePath}:${site.enclosing}:${Trees.collapse(args.values.head.syntax, 80)}",
              s"publishTransactionally argument ${args.values.head.syntax} has no visible event type",
              site.loc
            )
          )
    }

  private def eventTypeOf(arg: Term): Option[String] = arg match {
    case Term.Apply.After_4_6_0(Term.Name(name), _) if isTypeName(name)                 => Some(name)
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(name), _), _) if isTypeName(name) => Some(name)
    case Term.Select(Term.Name(name), _) if isTypeName(name)                            => Some(name)
    case _                                                                              => None
  }

  private def isTypeName(name: String): Boolean = name.headOption.exists(_.isUpper)

  private def subscribesIn(file: ParsedFile, ownership: Ownership): List[(String, OutboxSubscriber)] =
    file.tree.collect {
      case call @ Term.Apply.After_4_6_0(Term.ApplyType.After_4_6_0(fun, targs), args)
          if Set("OutboxSubscription", "OutboxSubscription.withDelivery").contains(fun.syntax) && targs.values.size == 1 =>
        args.values.headOption.flatMap(Trees.stringLiteral).map { consumer =>
          targs.values.head.syntax -> OutboxSubscriber(consumer, Site(ownership.ownerOf(file.pkg), Trees.enclosingName(call), Trees.loc(file, call)))
        }
    }.flatten
}
