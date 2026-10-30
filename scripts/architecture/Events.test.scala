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
  private val result           = EventExtractor.extract(files, Ownership.of(files))
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
    assertEquals(EventExtractor.extract(files, Ownership.of(files)).unclassified.map(_.key), List("topic-name:shop/ShopModule.scala:OrderEvent"))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
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
    assertEquals(EventExtractor.extract(files, Ownership.of(files)).value._2.map(_.eventType), List("a-created.v1"))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
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
    val result = EventExtractor.extract(files, Ownership.of(files))
    assertEquals(result.value._1.map(t => (t.name, t.publishers, t.subscribers)), List(("notices_a", Nil, Nil), ("notices_b", Nil, Nil)))
    assertEquals(
      result.unclassified.map(u => u.key -> u.description),
      List("bus-ambiguous:a/routers/ARouter.scala:Notice" -> "EventBus[Notice] in ARouter could be topic notices_a or notices_b")
    )
    assertEquals(result.warnings, Nil)
  }
}
