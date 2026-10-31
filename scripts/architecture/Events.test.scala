package architecture

class EventsSuite extends munit.FunSuite {
  private val files = Fixtures.parsed(
    Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
    Fixtures.src(
      "user/domain/UserDeleted.scala",
      "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
    ),
    Fixtures.src(
      "user/services/UserService.scala",
      "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n  def e(ev: UserDeleted) = outbox.publishTransactionally(ev)\n}"
    ),
    Fixtures.src(
      "shop/ShopModule.scala",
      "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[ShopEvent] = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[UserDeleted](\"shop\")(h)\n}"
    ),
    Fixtures.src(
      "shop/routers/ShopRouter.scala",
      "package app.shop.routers\nclass ShopRouter(eventBus: EventBus[ShopEvent]) {\n  def ws = eventBus.subscribeAwait\n}"
    )
  )
  private val result           = EventExtractor.extract(files, Ownership.of(files), "app")
  private val (topics, events) = result.value

  test("outbox event with publisher and subscriber") {
    assertEquals(events.size, 1)
    val e = events.head
    assertEquals((e.typeName, e.eventType, e.owner), ("UserDeleted", "user-deleted.v1", Owner.Module("user")))
    assertEquals(e.publishers.map(_.enclosing), List("UserService"))
    assertEquals(e.subscribers.map(s => s.consumer -> s.site.owner), List("shop" -> Owner.Module("shop")))
  }

  test("an outbox publish without a visible type is unclassified, not guessed") {
    assertEquals(result.unclassified.map(_.key), List("outbox-publish:user/services/UserService.scala:UserService:ev"))
  }

  test("a bus topic with a subscriber and no publisher warns") {
    assertEquals(topics.map(t => (t.name, t.eventType, t.subscribers.map(_.enclosing))), List(("shop_events", "ShopEvent", List("ShopRouter"))))
    assertEquals(result.warnings.map(_.key), List("no-publisher:topic:shop_events"))
  }

  test("a topic whose name is not a literal is unclassified") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val bus = eventBusRuntime.topic[OrderEvent](Topics.Orders, maxQueued = 8)\n}"
      )
    )
    assertEquals(
      EventExtractor.extract(files, Ownership.of(files), "app").unclassified.map(_.key),
      List("topic-name:shop/ShopModule.scala:OrderEvent")
    )
  }

  test("a topic name two declarations share is one topic with the uses of both, and a warning") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "a/AModule.scala",
        "package app.a\ntrait AModule {\n  lazy val bus: EventBus[AEvent] = eventBusRuntime.topic[AEvent](\"shared\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "b/BModule.scala",
        "package app.b\ntrait BModule {\n  lazy val bus: EventBus[BEvent] = eventBusRuntime.topic[BEvent](\"shared\", maxQueued = 8)\n}"
      ),
      Fixtures.src("a/services/APublisher.scala", "package app.a.services\nclass APublisher(bus: EventBus[AEvent]) {\n  def p = bus.publish\n}"),
      Fixtures.src("b/routers/BRouter.scala", "package app.b.routers\nclass BRouter(bus: EventBus[BEvent]) {\n  def s = bus.subscribe\n}")
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._1.map(t => (t.name, t.eventType, t.owner, t.publishers.map(_.enclosing), t.subscribers.map(_.enclosing))),
      List(("shared", "AEvent", Owner.Module("a"), List("APublisher"), List("BRouter")))
    )
    assertEquals(
      result.warnings.map(w => w.key -> w.message),
      List("duplicate-topic:shared" -> "topic shared is declared by topic[AEvent] in a, topic[BEvent] in b")
    )
  }

  test("an event type two descriptors share is one event with the uses of both, and a warning") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
      Fixtures.src(
        "a/domain/AEvent.scala",
        "package app.a.domain\nfinal case class AEvent(id: Id)\nobject AEvent {\n  given DomainEventDescriptor[AEvent] = DomainEventDescriptor(\"shared.v1\", \"a\", _.id)\n}"
      ),
      Fixtures.src(
        "b/domain/BEvent.scala",
        "package app.b.domain\nfinal case class BEvent(id: Id)\nobject BEvent {\n  given DomainEventDescriptor[BEvent] = DomainEventDescriptor(\"shared.v1\", \"b\", _.id)\n}"
      ),
      Fixtures.src(
        "b/services/BService.scala",
        "package app.b.services\nclass BService(outbox: Outbox) {\n  def p(id: Id) = outbox.publishTransactionally(BEvent(id))\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._2.map(e => (e.eventType, e.typeName, e.owner, e.publishers.map(_.enclosing))),
      List(("shared.v1", "AEvent", Owner.Module("a"), List("BService")))
    )
    assertEquals(
      result.warnings.map(w => w.key -> w.message),
      List("duplicate-event:shared.v1" -> "event shared.v1 is declared by AEvent in a, BEvent in b")
    )
  }

  test("a descriptor with an explicit type argument is an outbox event") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src(
        "a/domain/Created.scala",
        "package app.a.domain\nfinal case class Created(id: Id)\nobject Created {\n  given DomainEventDescriptor[Created] = DomainEventDescriptor[Created](\"a-created.v1\", \"a\", _.id)\n}"
      )
    )
    assertEquals(EventExtractor.extract(files, Ownership.of(files), "app").value._2.map(_.eventType), List("a-created.v1"))
  }

  test("a topic named by a named argument, and topic and event warnings that can't share a key") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "a/AModule.scala",
        "package app.a\ntrait AModule {\n  lazy val bus: EventBus[Same] = eventBusRuntime.topic[Same](name = \"same\", maxQueued = 8)\n}"
      ),
      Fixtures.src("a/routers/ARouter.scala", "package app.a.routers\nclass ARouter(bus: EventBus[Same]) {\n  def s = bus.subscribe\n}"),
      Fixtures.src(
        "a/domain/Same.scala",
        "package app.a.domain\nfinal case class Same(id: Id)\nobject Same {\n  given DomainEventDescriptor[Same] = DomainEventDescriptor(\"same\", \"a\", _.id)\n}"
      ),
      Fixtures.src("a/AModuleSubscriptions.scala", "package app.a\nobject Subs {\n  val s = OutboxSubscription[Same](\"a\")(h)\n}")
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._1.map(_.name), List("same"))
    assertEquals(result.warnings.map(_.key).sorted, List("no-publisher:event:same", "no-publisher:topic:same"))
  }

  test("a bus used in a module trait or an object is a publisher") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val userBus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n" +
          "  lazy val starter = Lifecycle(userBus.publish(UserEvent.Started))\n}"
      ),
      Fixtures.src(
        "user/services/Pinger.scala",
        "package app.user.services\nobject Pinger {\n  val bus: EventBus[UserEvent] = ???\n  def ping = bus.publish(UserEvent.Ping)\n}"
      ),
      Fixtures.src(
        "user/routers/UserRouter.scala",
        "package app.user.routers\nclass UserRouter(bus: EventBus[UserEvent]) {\n  def ws = bus.subscribe\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._1.map(t => (t.publishers.map(_.enclosing), t.subscribers.map(_.enclosing))),
      List((List("UserModule", "Pinger"), List("UserRouter")))
    )
    assertEquals(result.warnings, Nil)
  }

  test("publishTransactionally with an explicit type argument publishes that type") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(ev: Ev) = outbox.publishTransactionally[UserDeleted](ev)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._2.map(_.publishers.map(_.enclosing)), List(List("UserService")))
    assertEquals(result.unclassified, Nil)
  }
  test("a bus whose payload type two topics share is unclassified, not given to both topics") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "a/AModule.scala",
        "package app.a\ntrait AModule {\n  lazy val first: EventBus[Notice] = eventBusRuntime.topic[Notice](\"notices_a\", maxQueued = 8)\n" +
          "  lazy val second: EventBus[Notice] = eventBusRuntime.topic[Notice](\"notices_b\", maxQueued = 8)\n}"
      ),
      Fixtures.src("a/routers/ARouter.scala", "package app.a.routers\nclass ARouter(bus: EventBus[Notice]) {\n  def s = bus.subscribe\n}")
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._1.map(t => (t.name, t.publishers, t.subscribers)), List(("notices_a", Nil, Nil), ("notices_b", Nil, Nil)))
    assertEquals(
      result.unclassified.map(u => u.key -> u.description),
      List("bus-ambiguous:a/routers/ARouter.scala:Notice" -> "EventBus[Notice] in ARouter could be topic notices_a or notices_b")
    )
    assertEquals(result.warnings, Nil)
  }
  test("an outbox subscription or event type that isn't a literal is unclassified, and its publishers aren't called unknown") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(EventTypes.Deleted, \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
      ),
      Fixtures.src(
        "user/UserSubscriptions.scala",
        "package app.user\nobject Subs {\n  val s = OutboxSubscription[UserDeleted](Consumers.Billing)(h)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._2, Nil)
    assertEquals(
      result.unclassified.map(u => u.key -> u.description),
      List(
        "outbox-event-name:user/domain/UserDeleted.scala:UserDeleted" -> "DomainEventDescriptor[UserDeleted] in UserDeleted has the event type EventTypes.Deleted, not a string literal",
        "outbox-subscription:user/UserSubscriptions.scala:UserDeleted" -> "OutboxSubscription[UserDeleted] in Subs names its consumer by Consumers.Billing, not a string literal"
      )
    )
  }
  test("a bus declared without a type annotation is known by its topic's type") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val shopBus = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n" +
          "  lazy val starter = Lifecycle(shopBus.publish(ShopEvent.Started))\n}"
      )
    )
    assertEquals(EventExtractor.extract(files, Ownership.of(files), "app").value._1.map(_.publishers.map(_.enclosing)), List(List("ShopModule")))
  }

  test("a bus used through this is a publisher") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val userBus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "user/services/Pinger.scala",
        "package app.user.services\nclass Pinger(bus: EventBus[UserEvent]) {\n  def ping = this.bus.publish(UserEvent.Ping)\n}"
      )
    )
    assertEquals(EventExtractor.extract(files, Ownership.of(files), "app").value._1.map(_.publishers.map(_.enclosing)), List(List("Pinger")))
  }

  test("a bus used through another object, or declared by a parent, is found by its name when one bus type has that name") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val userBus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "user/services/Notifier.scala",
        "package app.user.services\nclass Notifier(deps: Deps) {\n  def ping = deps.userBus.publish(UserEvent.Ping)\n}"
      ),
      Fixtures.src(
        "user/services/Listener.scala",
        "package app.user.services\nclass Listener extends UserModule {\n  def run = userBus.subscribe(handle)\n}"
      )
    )
    val topic = EventExtractor.extract(files, Ownership.of(files), "app").value._1.head
    assertEquals((topic.publishers.map(_.enclosing), topic.subscribers.map(_.enclosing)), (List("Notifier"), List("Listener")))
  }

  test("a selected bus whose name several bus types share, or no bus has, is not guessed") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val bus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[ShopEvent] = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "shop/services/Feed.scala",
        "package app.shop.services\nclass Feed(deps: Deps) {\n  def a = deps.bus.publish(ShopEvent.Opened)\n  def b = deps.topic.publish(Tick)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._1.map(_.publishers), List(Nil, Nil))
    assertEquals(result.unclassified, Nil)
  }

  test("a bus passed as a parameter or bound locally is known by its own type, not by another bus's name") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[ShopEvent] = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "audit/AuditModule.scala",
        "package app.audit\ntrait AuditModule {\n  lazy val auditBus: EventBus[AuditEvent] = eventBusRuntime.topic[AuditEvent](\"audit_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "audit/services/AuditFanout.scala",
        """package app.audit.services
          |object AuditFanout {
          |  def send(bus: EventBus[AuditEvent], e: AuditEvent) = bus.publish(e)
          |  def each(buses: List[Bus]) = buses.foreach(bus => bus.publish(Tick))
          |  def loop = for { bus <- open } yield bus.publish(Tick)
          |  def local = { val bus = open; bus.publish(Tick) }
          |  def matched(x: Any) = x match { case bus: Bus => bus.publish(Tick) }
          |}""".stripMargin
      )
    )
    assertEquals(
      EventExtractor.extract(files, Ownership.of(files), "app").value._1.map(t => t.name -> t.publishers.map(_.enclosing)),
      List("audit_events" -> List("AuditFanout"), "shop_events" -> Nil)
    )
  }

  test("a bus typed by a type parameter is generic plumbing, neither a use nor unclassified") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "utils/events/Relay.scala",
        "package app.utils.events\nfinal class Relay[E](bus: EventBus[E]) {\n  def send(e: E) = bus.publish(e)\n}\nobject Fan {\n  def all[A](bus: EventBus[A], a: A) = bus.publish(a)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals((result.value._1, result.unclassified), (Nil, Nil))
  }

  test("a class that publishes in two places is listed once") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val userBus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user.deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(bus: EventBus[UserEvent], outbox: Outbox) {\n  def a = bus.publish(UserEvent.A)\n  def b = bus.publish(UserEvent.B)\n" +
          "  def c(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
      )
    )
    val (topics, events) = EventExtractor.extract(files, Ownership.of(files), "app").value
    assertEquals(
      (topics.map(_.publishers.map(_.enclosing)), events.map(_.publishers.map(_.enclosing))),
      (List(List("UserService")), List(List("UserService")))
    )
  }

  test("a bus declared at the top level of a file is known by its type") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[OrderEvent] = eventBusRuntime.topic[OrderEvent](\"orders\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "shop/services/Announcer.scala",
        "package app.shop.services\nval orderBus: EventBus[OrderEvent] = ???\ndef announce = orderBus.publish(OrderEvent.Placed)"
      )
    )
    assertEquals(EventExtractor.extract(files, Ownership.of(files), "app").value._1.map(_.publishers.map(_.enclosing)), List(List("Announcer")))
  }

  test("a bus bound by a typed pattern is known by that type") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  lazy val bus: EventBus[UserEvent] = eventBusRuntime.topic[UserEvent](\"user_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\ntrait ShopModule {\n  lazy val bus: EventBus[ShopEvent] = eventBusRuntime.topic[ShopEvent](\"shop_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "user/services/Pinger.scala",
        "package app.user.services\nobject Pinger {\n  def on(x: Any) = x match {\n    case bus: EventBus[UserEvent] => bus.publish(UserEvent.Ping)\n  }\n}"
      )
    )
    assertEquals(
      EventExtractor.extract(files, Ownership.of(files), "app").value._1.map(t => t.name -> t.publishers.map(_.enclosing)),
      List("shop_events" -> Nil, "user_events" -> List("Pinger"))
    )
  }

  test("an event published by its nested type, its enum's case or an explicit type argument matches its descriptor") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "user/UserModule.scala",
        "package app.user\ntrait UserModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[UserEvent.Deleted](\"audit\")(h)\n}"
      ),
      Fixtures.src(
        "user/domain/UserEvent.scala",
        "package app.user.domain\nsealed trait UserEvent\nobject UserEvent {\n  final case class Deleted(id: Id) extends UserEvent\n  given DomainEventDescriptor[UserEvent.Deleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/domain/ShopEvent.scala",
        "package app.user.domain\nenum ShopEvent {\n  case Opened(id: Id)\n}\nobject ShopEvent {\n  given DomainEventDescriptor[ShopEvent] = DomainEventDescriptor(\"shop.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserEvent.Deleted(id))\n  def o(id: Id) = outbox.publishTransactionally(ShopEvent.Opened(id))\n}"
      ),
      Fixtures.src(
        "user/services/AuditService.scala",
        "package app.user.services\nclass AuditService(outbox: Outbox) {\n  def d(ev: UserEvent.Deleted) = outbox.publishTransactionally[UserEvent.Deleted](ev)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._2.map(e => (e.eventType, e.publishers.map(_.enclosing).sorted)),
      List("shop.v1" -> List("UserService"), "user-deleted.v1" -> List("AuditService", "UserService"))
    )
    assertEquals(result.warnings, Nil)
    assertEquals(result.unclassified, Nil)
  }

  test("a payload type two modules both name links a bus by the module the file imports it from, else by the file's own module") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "a/AModule.scala",
        "package app.a\nimport app.a.domain.Event\ntrait AModule {\n  lazy val bus: EventBus[Event] = eventBusRuntime.topic[Event](\"a_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src(
        "b/BModule.scala",
        "package app.b\nimport app.b.domain.Event\ntrait BModule {\n  lazy val bus: EventBus[Event] = eventBusRuntime.topic[Event](\"b_events\", maxQueued = 8)\n}"
      ),
      Fixtures.src("c/CModule.scala", "package app.c\ntrait CModule"),
      Fixtures.src("a/domain/Event.scala", "package app.a.domain\nfinal case class Event(id: Id)"),
      Fixtures.src("b/domain/Event.scala", "package app.b.domain\nfinal case class Event(id: Id)"),
      Fixtures.src(
        "a/services/APublisher.scala",
        "package app.a.services\nimport app.a.domain.Event\nclass APublisher(bus: EventBus[Event]) {\n  def p = bus.publish\n}"
      ),
      Fixtures.src(
        "b/services/BPublisher.scala",
        "package app.b.services\nimport app.b.domain.*\nclass BPublisher(bus: EventBus[Event]) {\n  def p = bus.publish\n}"
      ),
      Fixtures.src(
        "b/routers/ARouter.scala",
        "package app.b.routers\nimport app.a.domain.Event\nclass ARouter(bus: EventBus[Event]) {\n  def s = bus.subscribe\n}"
      ),
      Fixtures.src("b/domain/BHandler.scala", "package app.b.domain\nclass BHandler(bus: EventBus[Event]) {\n  def s = bus.subscribe\n}"),
      Fixtures.src("c/services/CListener.scala", "package app.c.services\nclass CListener(bus: EventBus[Event]) {\n  def s = bus.subscribe\n}")
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._1.map(t => (t.name, t.publishers.map(_.enclosing), t.subscribers.map(_.enclosing))),
      List(("a_events", List("APublisher"), List("ARouter")), ("b_events", List("BPublisher"), List("BHandler")))
    )
    assertEquals(result.unclassified.map(_.key), List("bus-ambiguous:c/services/CListener.scala:Event"))
  }

  test("an event nested in an object matches a descriptor that names it by its simple or its qualified name, from publishers and subscribers") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/Events.scala",
        "package app.user.domain\nobject Events {\n  final case class UserDeleted(id: Id)\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/domain/Orders.scala",
        "package app.user.domain\nobject Orders {\n  final case class Placed(id: Id)\n}\ngiven DomainEventDescriptor[Orders.Placed] = DomainEventDescriptor(\"order-placed.v1\", \"user\", _.id)"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nimport app.user.domain.Orders.*\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(Events.UserDeleted(id))\n  def p(id: Id) = outbox.publishTransactionally(Placed(id))\n}"
      ),
      Fixtures.src(
        "billing/BillingModule.scala",
        "package app.billing\ntrait BillingModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Events.UserDeleted](\"billing\")(h)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._2.map(e => (e.eventType, e.publishers.map(_.enclosing), e.subscribers.map(_.consumer))),
      List(("order-placed.v1", List("UserService"), Nil), ("user-deleted.v1", List("UserService"), List("billing")))
    )
    assertEquals(result.unclassified, Nil)
  }

  test("payload types two modules both name link an outbox event by the module the file imports the type from, else by its own module") {
    val files = Fixtures.parsed(
      Fixtures.src("order/OrderModule.scala", "package app.order\ntrait OrderModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "order/domain/OrderEvents.scala",
        "package app.order.domain\nobject OrderEvents {\n  final case class Created(id: Id)\n  given DomainEventDescriptor[Created] = DomainEventDescriptor(\"order-created.v1\", \"order\", _.id)\n}"
      ),
      Fixtures.src(
        "user/domain/UserEvents.scala",
        "package app.user.domain\nobject UserEvents {\n  final case class Created(id: Id)\n  given DomainEventDescriptor[Created] = DomainEventDescriptor(\"user-created.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "order/services/OrderService.scala",
        "package app.order.services\nimport app.order.domain.OrderEvents.*\nclass OrderService(outbox: Outbox) {\n  def c(id: Id) = outbox.publishTransactionally(Created(id))\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nimport app.user.domain.UserEvents\nclass UserService(outbox: Outbox) {\n  def c(id: Id) = outbox.publishTransactionally(UserEvents.Created(id))\n}"
      ),
      Fixtures.src(
        "billing/BillingModule.scala",
        "package app.billing\nimport app.user.domain.UserEvents.Created\ntrait BillingModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Created](\"billing\")(h)\n}"
      ),
      Fixtures.src(
        "c/CModule.scala",
        "package app.c\ntrait CModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Created](\"c\")(h)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._2.map(e => (e.eventType, e.publishers.map(_.enclosing), e.subscribers.map(_.consumer))),
      List(("order-created.v1", List("OrderService"), Nil), ("user-created.v1", List("UserService"), List("billing")))
    )
    assertEquals(
      result.unclassified.map(u => u.key -> u.description),
      List("outbox-event-ambiguous:c/CModule.scala:Created" -> "Created in CModule could be event order-created.v1 or user-created.v1")
    )
  }

  test(
    "a subscription through an imported withDelivery and a descriptor built with apply are found, and a descriptor built otherwise is unclassified"
  ) {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor.apply(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/domain/UserRenamed.scala",
        "package app.user.domain\nfinal case class UserRenamed(id: Id)\nobject UserRenamed {\n  given DomainEventDescriptor[UserRenamed] = Descriptors.of(\"user-renamed.v1\")\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n  def r(id: Id) = outbox.publishTransactionally(UserRenamed(id))\n}"
      ),
      Fixtures.src(
        "billing/BillingModule.scala",
        "package app.billing\nimport app.utils.events.outbox.OutboxSubscription.withDelivery\ntrait BillingModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ withDelivery[UserDeleted](\"billing\")(h)\n}"
      ),
      Fixtures.src(
        "utils/events/outbox/OutboxSubscription.scala",
        "package app.utils.events.outbox\nobject OutboxSubscription {\n  def apply[A](consumer: String)(handler: A => R)(using DomainEventDescriptor[A]) = withDelivery[A](consumer)((a, _) => handler(a))\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._2.map(e => (e.eventType, e.publishers.map(_.enclosing), e.subscribers.map(_.consumer))),
      List(("user-deleted.v1", List("UserService"), List("billing")))
    )
    assertEquals(result.unclassified.map(_.key), List("outbox-event-descriptor:user/domain/UserRenamed.scala:UserRenamed"))
  }

  test("a bus whose payload type is written qualified on one side and simple on the other links to its topic") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\nimport app.shop.domain.Events\ntrait ShopModule {\n  lazy val bus: EventBus[Events.Order] = eventBusRuntime.topic[Events.Order](\"orders\", maxQueued = 8)\n}"
      ),
      Fixtures.src("shop/domain/Events.scala", "package app.shop.domain\nobject Events {\n  final case class Order(id: Id)\n}"),
      Fixtures.src(
        "shop/services/OrderService.scala",
        "package app.shop.services\nimport app.shop.domain.Events.Order\nclass OrderService(bus: EventBus[Order]) {\n  def p = bus.publish\n}"
      ),
      Fixtures.src(
        "shop/routers/OrderRouter.scala",
        "package app.shop.routers\nimport app.shop.domain.Events\nclass OrderRouter(bus: EventBus[Events.Order]) {\n  def s = bus.subscribe\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(
      result.value._1.map(t => (t.name, t.publishers.map(_.enclosing), t.subscribers.map(_.enclosing))),
      List(("orders", List("OrderService"), List("OrderRouter")))
    )
    assertEquals(result.unclassified, Nil)
    assertEquals(result.warnings, Nil)
  }

  test("an event built with new or typed by an ascription has a visible type") {
    val files = Fixtures.parsed(
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src(
        "user/domain/UserDeleted.scala",
        "package app.user.domain\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}"
      ),
      Fixtures.src(
        "user/services/UserService.scala",
        "package app.user.services\nclass UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(new UserDeleted(id))\n}"
      ),
      Fixtures.src(
        "user/services/AuditService.scala",
        "package app.user.services\nclass AuditService(outbox: Outbox) {\n  def d(ev: Any) = outbox.publishTransactionally(ev: UserDeleted)\n}"
      )
    )
    val result = EventExtractor.extract(files, Ownership.of(files), "app")
    assertEquals(result.value._2.map(_.publishers.map(_.enclosing)), List(List("UserService", "AuditService")))
    assertEquals(result.unclassified, Nil)
  }
}
