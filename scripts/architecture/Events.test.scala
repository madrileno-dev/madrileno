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
    assertEquals(result.warnings.map(_.key), List("no-publisher:shop_events"))
  }
}
