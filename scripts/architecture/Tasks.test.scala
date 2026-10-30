package architecture

class TasksSuite extends munit.FunSuite {
  private val files = Fixtures.parsed(
    Fixtures.src("auction/AuctionModule.scala", "package app.auction\ntrait AuctionModule"),
    Fixtures.src("mail/MailModule.scala", "package app.mail\ntrait MailModule"),
    Fixtures.src(
      "auction/services/AuctionService.scala",
      "package app.auction.services\nclass AuctionService {\n" +
        "  val closeExpired = Task.recurring(\"close-expired-auctions\", Schedule.Cron(CronExpression.unsafeParse(\"0 * * ? * *\")))(_ => IO.unit)\n" +
        "  val sweep = Task.recurring(\"sweep\", Schedule.RecurringWithFixedRate(5.minutes))(_ => IO.unit)\n" +
        "  val analyze = Task.oneTime(TaskDescriptor[Id](\"analyze-image\"))(_ => IO.unit)\n" +
        "  def upload(id: Id) = scheduler.scheduleTransactionally(analyze.instance(s\"a-$id\", id))\n}"
    ),
    Fixtures.src(
      "mail/Mailer.scala",
      "package app.mail\nclass Mailer {\n" +
        "  val sendMail = Task.oneTime(TaskDescriptor[Mail](\"send-mail\"))(_ => IO.unit)\n" +
        "  val retarget = Task.custom(TaskDescriptor[Int](\"retarget\"))(_ => IO.unit)\n}"
    ),
    Fixtures.src(
      "auction/services/Notifier.scala",
      "package app.auction.services\nclass Notifier(mailer: Mailer) {\n  def notify(m: Mail) = scheduler.schedule(mailer.sendMail.instance(\"n\", m))\n}"
    )
  )
  private val tasks = TaskExtractor.extract(files, Ownership.of(files)).value

  test("tasks with their kind, schedule, owner and where they are scheduled from") {
    assertEquals(
      tasks.map(t => (t.name, t.kind, t.schedule, t.owner, t.scheduledFrom.map(s => s.owner -> s.enclosing))),
      List(
        ("analyze-image", TaskKind.OneTime, None, Owner.Module("auction"), List(Owner.Module("auction") -> "AuctionService")),
        ("close-expired-auctions", TaskKind.Recurring, Some("cron 0 * * ? * *"), Owner.Module("auction"), Nil),
        ("retarget", TaskKind.Custom, None, Owner.Module("mail"), Nil),
        ("send-mail", TaskKind.OneTime, None, Owner.Module("mail"), List(Owner.Module("auction") -> "Notifier")),
        ("sweep", TaskKind.Recurring, Some("every 5.minutes"), Owner.Module("auction"), Nil)
      )
    )
  }

  test("a task whose name is computed is listed under the expression that names it") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "outbox/OutboxDispatcher.scala",
        "package app.outbox\nclass OutboxDispatcher {\n" +
          "  val deliveryTasks = consumers.map(c => OneTimeTask[UUID](descriptor = TaskDescriptor[UUID](OutboxDeliveryTasks.taskName(c)), execution = _ => IO.unit))\n}"
      )
    )
    assertEquals(
      TaskExtractor.extract(files, Ownership.of(files)).value.map(t => (t.name, t.kind)),
      List(("OutboxDeliveryTasks.taskName(c)", TaskKind.OneTime))
    )
  }

  test("named arguments give the name and the schedule") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "probe/Prober.scala",
        "package app.probe\nclass Prober {\n" +
          "  val restart = Task.recurring(name = \"restart-prober\", schedule = Schedule.RecurringWithFixedDelay(10.seconds))(_ => IO.unit)\n" +
          "  val render = Task.custom(TaskDescriptor[Int](taskName = \"render\"))(_ => IO.unit)\n}"
      )
    )
    assertEquals(
      TaskExtractor.extract(files, Ownership.of(files)).value.map(t => (t.name, t.schedule)),
      List(("render", None), ("restart-prober", Some("after 10.seconds")))
    )
  }

  test("code that builds tasks from a descriptor it was given is not a task") {
    val files = Fixtures.parsed(
      Fixtures.src(
        "utils/task/Task.scala",
        "package app.utils.task\nobject Task {\n  def oneTime[A](descriptor: TaskDescriptor[A])(execution: Task[A] => IO[Unit]) = OneTimeTask(descriptor, execution)\n}"
      )
    )
    assertEquals(TaskExtractor.extract(files, Ownership.of(files)).value, Nil)
  }

  private def shop(task: String) = Fixtures.project(
    Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule"),
    Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
    Fixtures.src("shop/services/ShopService.scala", s"package app.shop.services\nclass ShopService {\n$task\n}")
  )
  private val nightly = "  val purge = Task.recurring(\"purge-carts\", Schedule.Cron(CronExpression.unsafeParse(\"0 0 3 * * ?\")))(_ => IO.unit)"
  private val hourly  = nightly.replace("0 0 3 * * ?", "0 0 * * * ?")

  test("a diff marks a task added or changed, and the module that owns it changed") {
    val added   = ArchDiff.compare(Extractor.extract(shop("")), Extractor.extract(shop(nightly)), "b", "h")
    val changed = ArchDiff.compare(Extractor.extract(shop(nightly)), Extractor.extract(shop(hourly)), "b", "h")
    assertEquals(added.tasks.map(t => t.key -> t.status), List("shop:purge-carts" -> Status.Added))
    assertEquals(changed.tasks.map(t => t.key -> t.status), List("shop:purge-carts" -> Status.Changed))
    assertEquals(changed.modules.map(m => m.id -> m.status), List("shop" -> Status.Changed))
  }

  test("the page lists tasks and the pull request output lists the changed ones") {
    val diff = ArchDiff.compare(Extractor.extract(shop(nightly)), Extractor.extract(shop(hourly)), "main", "working tree")
    val page = Html.render(diff)
    assert(page.contains("""<section id="tasks"><h2>Tasks</h2>"""), page)
    assert(page.contains("purge-carts") && page.contains("cron 0 0 * * * ?"))
    assert(Mermaid.render(diff).contains("**Tasks**\n\n- changed `purge-carts`: recurring, cron `0 0 * * * ?`, in shop"), Mermaid.render(diff))
  }

  test("same-named tasks in two modules stay apart, warn, and are matched to the calls in their own module") {
    val files = Fixtures.parsed(
      Fixtures.src("a/AModule.scala", "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
      Fixtures.src(
        "a/services/AService.scala",
        "package app.a.services\nclass AService {\n  val dup = Task.oneTime(TaskDescriptor[Int](\"dup\"))(_ => IO.unit)\n}"
      ),
      Fixtures.src(
        "b/services/BService.scala",
        "package app.b.services\nclass BService {\n  val dup = Task.oneTime(TaskDescriptor[Int](\"dup\"))(_ => IO.unit)\n  def go = scheduler.schedule(dup.instance(\"x\", 1))\n}"
      )
    )
    val result = TaskExtractor.extract(files, Ownership.of(files))
    assertEquals(
      result.value.map(t => (t.name, t.owner, t.scheduledFrom.map(_.enclosing))),
      List(("dup", Owner.Module("a"), Nil), ("dup", Owner.Module("b"), List("BService")))
    )
    assertEquals(result.warnings.map(w => w.key -> w.message), List("duplicate-task:dup" -> "task dup is declared in a, b"))
  }
}
