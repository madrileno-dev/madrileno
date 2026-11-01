package architecture

class MermaidSuite extends munit.FunSuite {
  private val diff = ArchDiff.compare(Fixtures.diffBase, Fixtures.diffHead, "main@abc1234", "working tree")
  private val out  = Mermaid.render(diff)

  test("header, flowchart and class definitions") {
    assert(out.startsWith("### Architecture changes: `main@abc1234` → `working tree`\n\n```mermaid\n---\nconfig:\n  theme: base\n"), out)
    assert(out.contains("---\nflowchart LR\n"), out)
    assert(out.contains("classDef added fill:#2f7d4a,stroke:#1f5c35,color:#ffffff"))
  }

  test("added module is a subgraph with its added component, removed module is dashed, user is a neighbour") {
    assert(out.contains("subgraph module_order[\"#43;#160;order\"]\n    direction LR\n"), out)
    assert(out.contains("component_order_OrderService[\"#43;#160;OrderService (service)\"]"), out)
    assert(out.contains("module_shop[\"−#160;shop\"]"), out)
    assert(out.contains("module_user[\"user\"]"), out)
    assert(out.contains("module_shop -.->|\"wires userRepository\"| module_user"), out)
    assert(out.contains("module_order -->|\"wires userRepository\"| module_user"), out)
  }

  test("labels are escaped") {
    val node = GNode("table:\"x<y>\"", "\"x<y>\"", NodeKind.Table, None, Status.Added)
    assertEquals(Mermaid.nodeLine(node), s"${Mermaid.nodeId(node.key)}[(\"#43;#160;#quot;x#lt;y#gt;#quot;\")]")
    assert(Mermaid.nodeId(node.key).matches("table__x_y___[0-9a-f]{8}"), Mermaid.nodeId(node.key))
  }

  test("no label starts with a Markdown list marker, which Mermaid 11 renders as an error") {
    assert(!"""\["\+ """.r.findFirstIn(out).isDefined, out)
  }

  test("an unchanged diff says so and has no code block") {
    val same = ArchDiff.compare(Fixtures.diffHead, Fixtures.diffHead, "main@abc1234", "working tree")
    assertEquals(
      Mermaid.render(same),
      "No architecture changes between `main@abc1234` and `working tree`.\n\n<sub>Mapped by Madrileño's <code>scripts/architecture.scala</code></sub>\n"
    )
  }

  test("more than 30 boxes collapse components") {
    val many = (1 to 31).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module {\n  private val s = wire[S$i]\n}")) ++
      (1 to 31).map(i => Fixtures.src(s"m$i/services/S$i.scala", s"package app.m$i.services\nclass S$i"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val graph = GraphView.changes(ArchDiff.compare(empty, Extractor.extract(Fixtures.project(many*)), "b", "h"))
    assert(graph.collapsed)
    assert(graph.nodes.forall(_.kind != NodeKind.Component))
  }

  test("a diagram too large for GitHub becomes a textual summary") {
    val many  = (1 to 90).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val text  = Mermaid.render(ArchDiff.compare(empty, Extractor.extract(Fixtures.project(many*)), "b", "h"))
    assert(!text.contains("```mermaid"), text.take(300))
    assert(text.contains("**Added modules (90):** m1, m10, m11"), text.take(600))
  }

  test("edges between two unchanged neighbours are left out") {
    def model(extra: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with HubModule"),
        Fixtures.src("a/AModule.scala", "package app.a\nimport app.b.domain.X\ntrait AModule"),
        Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
        Fixtures.src("hub/HubModule.scala", s"package app.hub\nimport app.a.domain.Y\nimport app.b.domain.Z\ntrait HubModule {\n$extra\n}")
      )
    )
    val graph = GraphView.changes(ArchDiff.compare(model(""), model("  lazy val extra: Extra = ???"), "b", "h"))
    assertEquals(graph.nodes.map(_.key).toSet, Set("module:hub", "module:a", "module:b"))
    assertEquals(graph.edges.map(e => e.from -> e.to).toSet, Set("module:hub" -> "module:a", "module:hub" -> "module:b"))
  }

  test("an unchanged arrow between two modules is left out when one of them changed only its code") {
    def model(extra: String, helper: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule with HubModule"),
        Fixtures.src("a/AModule.scala", s"package app.a\nimport app.b.domain.X\ntrait AModule {\n$helper\n}"),
        Fixtures.src("b/BModule.scala", "package app.b\ntrait BModule"),
        Fixtures.src("hub/HubModule.scala", s"package app.hub\nimport app.a.domain.Y\nimport app.b.domain.Z\ntrait HubModule {\n$extra\n}")
      )
    )
    val graph = GraphView.changes(ArchDiff.compare(model("", ""), model("  lazy val extra: Extra = ???", "  private def helper = 1"), "b", "h"))
    assertEquals(graph.nodes.find(_.key == "module:a").map(_.codeChanged), Some(true))
    assertEquals(graph.edges.map(e => e.from -> e.to).toSet, Set("module:hub" -> "module:a", "module:hub" -> "module:b"))
  }

  test("every status marker is glued to its label with a non-breaking space") {
    assert(Mermaid.nodeLine(GNode("module:a", "a", NodeKind.Module, None, Status.Changed)).contains("~#160;a"))
    assert(Mermaid.nodeLine(GNode("module:a", "a", NodeKind.Module, None, Status.Removed)).contains("−#160;a"))
  }

  test("edge labels name what kind of dependency they carry") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.user.domain.UserId\nimport app.user.services.Users\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}"
        )
      )
    )
    val labels = GraphView.full(ArchDiff.full(model, "h"), Level.Modules).edges.map(_.label)
    assertEquals(labels, List("wires userRepository · imports domain · uses Users"))
  }

  test("labels name unwired internal types, leave out wired ones and cap the list") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
        Fixtures.src("user/repositories/UserRepository.scala", "package app.user.repositories\nclass UserRepository"),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\nimport app.user.repositories.UserRepository\nimport app.user.services.{A, B, C, D, E}\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}"
        )
      )
    )
    assertEquals(GraphView.full(ArchDiff.full(model, "h"), Level.Modules).edges.map(_.label), List("wires userRepository · uses A, B, C, +2 more"))
  }

  test("the diagram uses the Madrileño palette and credits it") {
    assert(out.contains("    primaryTextColor: \"#1e1e24\""), out)
    assert(out.contains("    clusterBkg: \"#f5f1ea\""), out)
    assert(!out.contains("fontFamily"), "a font GitHub lacks makes Mermaid measure and render with different fonts, clipping labels")
    assert(out.contains("<sub>Mapped by Madrileño's <code>scripts/architecture.scala</code></sub>"), out)
  }

  private def pair(cyclic: Boolean) = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends AModule with BModule"),
      Fixtures.src("a/AModule.scala", if (cyclic) "package app.a\nimport app.b.domain.X\ntrait AModule" else "package app.a\ntrait AModule"),
      Fixtures.src("b/BModule.scala", if (cyclic) "package app.b\nimport app.a.domain.Y\ntrait BModule" else "package app.b\ntrait BModule")
    )
  )

  test("a metrics table compares base and branch, lower is better") {
    val text = Mermaid.render(ArchDiff.compare(pair(cyclic = false), pair(cyclic = true), "main@abc1234", "working tree"))
    assert(text.contains("**Architecture metrics** (lower is better)"), text)
    assert(text.contains("| Metric | `main@abc1234` | `working tree` | |"), text)
    assert(text.contains("| Propagation cost | 0% | 100% | ▲ worse |"), text)
    assert(text.contains("| Largest cycle | 0 | 2 | ▲ worse |"), text)
    assert(text.contains("| Cake bypasses | 0 | 0 | same |"), text)
    assert(text.indexOf("**Architecture metrics**") < text.indexOf("**New warnings**"), text)
  }

  test("metrics that did not move collapse to one line") {
    def shop(service: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule"),
        Fixtures.src("shop/ShopModule.scala", s"package app.shop\ntrait ShopModule {\n  private val s = wire[$service]\n}"),
        Fixtures.src("shop/services/S.scala", s"package app.shop.services\nclass $service")
      )
    )
    val text = Mermaid.render(ArchDiff.compare(shop("Pricing"), shop("Billing"), "b", "h"))
    assert(
      text.contains(
        "**Architecture metrics** unchanged: propagation cost 0%, size concentration 100%, largest cycle 0, stable-dependency violations 0, cake bypasses 0, drift 0."
      ),
      text
    )
  }

  test("edge labels are quoted so consumer names with brackets cannot break the diagram") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src(
          "user/domain/Gone.scala",
          "package app.user.domain\nobject Gone {\n  given DomainEventDescriptor[Gone] = DomainEventDescriptor(\"gone.v1\", \"user\", _.id)\n}"
        ),
        Fixtures.src(
          "user/services/S.scala",
          "package app.user.services\nclass S(outbox: Outbox) {\n  def p(id: Id) = outbox.publishTransactionally(Gone(id))\n}"
        ),
        Fixtures.src(
          "shop/ShopModule.scala",
          "package app.shop\ntrait ShopModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[Gone](\"shop (cleanup) [v2]\")(h)\n}"
        )
      )
    )
    val text = Mermaid.render(ArchDiff.compare(ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil), model, "b", "h"))
    assert(text.contains("""-->|"as shop (cleanup) [v2]"| module_shop"""), text)
  }

  test("a given import names its package in the label") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\nimport app.user.services.given\ntrait ShopModule")
      )
    )
    assertEquals(GraphView.full(ArchDiff.full(model, "h"), Level.Modules).edges.map(_.label), List("uses services.given"))
  }

  test("a thick arrow marks another module's repositories used around the wiring, not a wired repository") {
    def shopArrow(shop: SourceFile*): String = {
      val model = Extractor.extract(
        Fixtures.project(
          (List(
            Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
            Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule {\n  lazy val userRepository: UserRepository = ???\n}"),
            Fixtures.src("user/repositories/UserRepository.scala", "package app.user.repositories\nclass UserRepository")
          ) ++ shop)*
        )
      )
      Mermaid.diagram(GraphView.full(ArchDiff.full(model, "h"), Level.Modules)).linesIterator.find(_.startsWith("  module_shop ")).get
    }
    val wired = shopArrow(
      Fixtures.src(
        "shop/ShopModule.scala",
        "package app.shop\nimport app.user.repositories.UserRepository\ntrait ShopModule {\n  lazy val userRepository: UserRepository\n}"
      )
    )
    val bypass = shopArrow(
      Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
      Fixtures.src("shop/services/S.scala", "package app.shop.services\nimport app.user.repositories.UserRepository\nclass S")
    )
    assert(wired.contains(" -->"), wired)
    assert(bypass.contains(" ==>"), bypass)
  }

  test("the full map renders as Mermaid with the current metrics and warnings") {
    val text = Mermaid.renderFull(ArchDiff.full(Fixtures.diffHead, "working tree"))
    assert(text.startsWith("### Architecture: `working tree`\n\n```mermaid\n---\nconfig:"), text)
    assert(text.contains("component_order_OrderService"), text)
    assert(text.contains("**Architecture metrics:** propagation cost 50%"), text)
  }
  test("a wildcard in a label is not Markdown emphasis") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\nimport app.user.repositories.*\nimport app.user.services.*\ntrait ShopModule")
      )
    )
    val text = Mermaid.diagram(GraphView.full(ArchDiff.full(model, "h"), Level.Modules))
    assert(text.contains("\"uses repositories.#42;, services.#42;\""), text)
  }
  test("a diagram with more text than Mermaid accepts becomes a textual summary") {
    val long  = "x" * 700
    val many  = (1 to 40).map(i => Fixtures.src(s"m$i$long/M${i}Module.scala", s"package app.m$i$long\ntrait M${i}Module"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val text  = Mermaid.render(ArchDiff.compare(empty, Extractor.extract(Fixtures.project(many*)), "b", "h"))
    assert(!text.contains("```mermaid"), text.take(300))
    assert(text.contains("**Added modules (40):**"), text.take(600))
  }

  test("node ids stay apart when keys differ only in punctuation") {
    val ids = List("topic:orders-events", "topic:orders_events", "module:user_profile", "module:user/profile").map(Mermaid.nodeId)
    assertEquals(ids.distinct.size, 4)
    assert(ids.forall(_.matches("[A-Za-z0-9_]+")), ids)
    assertEquals(Mermaid.nodeId("component:order:OrderService"), "component_order_OrderService")
  }

  test("a hash in a label is not read as the start of an entity") {
    val node = GNode("topic:t", "a#lt;b", NodeKind.Topic, None, Status.Unchanged)
    assert(Mermaid.nodeLine(node).contains("a#35;lt;b"), Mermaid.nodeLine(node))
  }

  test("underscores, tildes and backticks in a label are not Markdown") {
    val node = GNode("topic:t", "_a_ ~~b~~ `c`", NodeKind.Topic, None, Status.Unchanged)
    assert(Mermaid.nodeLine(node).contains("#95;a#95; #126;#126;b#126;#126; #96;c#96;"), Mermaid.nodeLine(node))
  }
  private def withFlags(shop: String) = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule with FeatureFlagModule"),
      Fixtures.src("shop/ShopModule.scala", s"package app.shop\ntrait ShopModule {\n$shop\n}"),
      Fixtures.src(
        "utils/featureflag/FeatureFlagModule.scala",
        "package app.utils.featureflag\ntrait FeatureFlagModule {\n  lazy val featureFlags: FeatureFlags = wire[FeatureFlags]\n}"
      ),
      Fixtures.src("utils/featureflag/FeatureFlags.scala", "package app.utils.featureflag\nclass FeatureFlags")
    )
  )

  test("a new or dropped dependency on an unchanged utils module is drawn") {
    val added   = Mermaid.render(ArchDiff.compare(withFlags(""), withFlags("  lazy val featureFlags: FeatureFlags"), "b", "h"))
    val dropped = Mermaid.render(ArchDiff.compare(withFlags("  lazy val featureFlags: FeatureFlags"), withFlags(""), "b", "h"))
    assert(added.contains("[\"utils/featureflag\"]") && added.contains("-->|\"wires featureFlags\"|"), added)
    assert(dropped.contains("[\"utils/featureflag\"]") && dropped.contains("-.->|\"wires featureFlags\"|"), dropped)
    assert(!Mermaid.render(ArchDiff.compare(withFlags(""), withFlags(""), "b", "h")).contains("featureflag"))
  }

  private def shopImporting(imports: String) = Extractor.extract(
    Fixtures.project(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
      Fixtures.src("shop/ShopModule.scala", s"package app.shop\n$imports\ntrait ShopModule")
    )
  )

  test("a type newly used from a layer the arrow already uses gets an arrow of its own") {
    val diff = ArchDiff.compare(
      shopImporting("import app.user.services.PriceCalculator"),
      shopImporting("import app.user.services.{PriceCalculator, TaxCalculator}"),
      "b",
      "h"
    )
    assertEquals(
      GraphView.changes(diff, Level.Modules, collapse = false).edges.map(e => (e.label, e.status)),
      List("uses TaxCalculator" -> Status.Added, "uses PriceCalculator" -> Status.Unchanged)
    )
  }

  test("an arrow for an imported event type says what it imports") {
    val model = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with BillingModule"),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src(
          "user/services/UserService.scala",
          "package app.user.services\nfinal case class UserDeleted(id: Id)\nobject UserDeleted {\n  given DomainEventDescriptor[UserDeleted] = DomainEventDescriptor(\"user-deleted.v1\", \"user\", _.id)\n}\n" +
            "class UserService(outbox: Outbox) {\n  def d(id: Id) = outbox.publishTransactionally(UserDeleted(id))\n}"
        ),
        Fixtures.src(
          "billing/BillingModule.scala",
          "package app.billing\nimport app.user.services.UserDeleted\ntrait BillingModule {\n  override abstract def outboxSubscriptions = super.outboxSubscriptions :+ OutboxSubscription[UserDeleted](\"billing\")(h)\n}"
        )
      )
    )
    val arrows = GraphView.full(ArchDiff.full(model, "h"), Level.Modules).edges.map(e => (e.from, e.to, e.label))
    assert(arrows.contains(("module:billing", "module:user", "imports services")), arrows)
  }

  test("more than 30 boxes without services or repositories hide nothing, and say nothing about hiding") {
    val many  = (1 to 31).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module"))
    val empty = ArchModel("app", Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil, Nil)
    val head  = Extractor.extract(Fixtures.project(many*))
    assert(!GraphView.changes(ArchDiff.compare(empty, head, "b", "h")).collapsed)
    assert(!Mermaid.render(ArchDiff.compare(empty, head, "b", "h")).contains("are hidden"))
    assert(!Mermaid.renderFull(ArchDiff.full(head, "h")).contains("are hidden"))
  }

  test("a table that only code outside the modules owns and uses is listed under the diagram, not left out") {
    def project(audit: Boolean) = Extractor.extract(
      Fixtures.projectWithMigrations(
        List(
          Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
          Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule")
        ) ++ Option
          .when(audit)(
            Fixtures.src(
              "utils/db/AuditLog.scala",
              "package app.utils.db\nobject AuditTable extends Table[AuditRow](\"audit\")\nclass AuditLog { def f = AuditTable }"
            )
          )
          .toList,
        Fixtures.migration("V1__audit.sql", "create table audit (id uuid primary key);") :: Nil
      )
    )
    val text = Mermaid.render(ArchDiff.compare(project(audit = false), project(audit = true), "b", "h"))
    assert(!text.contains("No architecture changes"), text)
    assert(text.contains("**Outside the modules**\n\n- added table `audit` in platform (utils/db)"), text)
  }

  test("a changed module says under the diagram what changed in its members and references") {
    def model(user: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", user),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
        Fixtures.src("shop/domain/Cart.scala", "package app.shop.domain\nfinal case class Cart(id: String)\nfinal case class Sku(id: String)")
      )
    )
    val text = Mermaid.render(
      ArchDiff.compare(
        model("package app.user\nimport app.shop.domain.Cart\ntrait UserModule"),
        model("package app.user\nimport app.shop.domain.{Cart, Sku}\ntrait UserModule {\n  val clock: Clock\n}"),
        "b",
        "h"
      )
    )
    assert(text.contains("**Changed modules**\n\n- user: now consumes `clock: Clock`; now imports `Sku` from shop domain"), text)
  }

  test("the full map's textual fallback lists only the modules the map shows") {
    val many = (1 to 90).map(i => Fixtures.src(s"m$i/M${i}Module.scala", s"package app.m$i\ntrait M${i}Module")) :+
      Fixtures.src("utils/flags/FlagsModule.scala", "package app.utils.flags\ntrait FlagsModule")
    val text    = Mermaid.renderFull(ArchDiff.full(Extractor.extract(Fixtures.project(many*)), "h"))
    val modules = text.linesIterator.find(_.startsWith("- **Modules")).getOrElse("")
    assert(modules.startsWith("- **Modules (90):**"), text.take(600))
    assert(!modules.contains("utils/flags"), modules)
  }

  test("a changed task says how its schedule and its callers changed") {
    def model(every: String, caller: Boolean) = Extractor.extract(
      Fixtures.project(
        List(
          Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends ShopModule"),
          Fixtures.src(
            "shop/ShopModule.scala",
            s"package app.shop\ntrait ShopModule {\n  val sweep = Task.recurring(\"sweep\", Schedule.RecurringWithFixedRate($every))(_ => IO.unit)\n  val send = Task.oneTime(TaskDescriptor[Int](\"send\"))(_ => IO.unit)\n}"
          )
        ) ++ Option
          .when(caller)(
            Fixtures.src(
              "shop/services/Sender.scala",
              "package app.shop.services\nclass Sender {\n  def go = scheduler.schedule(send.instance(\"x\", 1))\n}"
            )
          )
          .toList*
      )
    )
    val text = Mermaid.render(ArchDiff.compare(model("5.minutes", caller = false), model("10.minutes", caller = true), "b", "h"))
    assert(text.contains("- changed `send`: one-time, in shop; now scheduled from Sender"), text)
    assert(text.contains("- changed `sweep`: recurring, every `10.minutes`, in shop; was every `5.minutes`"), text)
  }

  test("a changed module says which classes it wires changed and whether it's mixed in now") {
    def model(mixedIn: Boolean, params: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src(
          "main/ApplicationLoader.scala",
          s"package app.main\nclass ApplicationLoader extends UserModule${if (mixedIn) " with HealthModule" else ""}"
        ),
        Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule"),
        Fixtures.src(
          "health/HealthModule.scala",
          "package app.health\ntrait HealthModule {\n  lazy val healthService: HealthService = wire[HealthService]\n}"
        ),
        Fixtures.src("health/services/HealthService.scala", s"package app.health.services\nclass HealthService($params)")
      )
    )
    val text = Mermaid.render(ArchDiff.compare(model(mixedIn = false, ""), model(mixedIn = true, "clock: Clock[IO]"), "b", "h"))
    assert(text.contains("- health: is now mixed into ApplicationLoader; `HealthService` now takes `Clock[IO]`"), text)
  }

  test("a wildcard or given import reads as words in the changed modules") {
    def model(user: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule with ShopModule"),
        Fixtures.src("user/UserModule.scala", user),
        Fixtures.src("shop/ShopModule.scala", "package app.shop\ntrait ShopModule"),
        Fixtures.src("shop/domain/Cart.scala", "package app.shop.domain\nfinal case class Cart(id: String)")
      )
    )
    val text = Mermaid.render(
      ArchDiff.compare(
        model("package app.user\nimport app.shop.domain.Cart\ntrait UserModule"),
        model("package app.user\nimport app.shop.domain.Cart\nimport app.shop.domain.*\nimport app.shop.domain.given\ntrait UserModule"),
        "b",
        "h"
      )
    )
    assert(text.contains("- user: now imports everything from shop domain; now imports the givens of shop domain"), text)
  }

  test("a second parameter of a type a class already takes, and a task's new kind, are explained") {
    def model(params: String, task: String) = Extractor.extract(
      Fixtures.project(
        Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
        Fixtures
          .src("user/UserModule.scala", s"package app.user\ntrait UserModule {\n  lazy val userRouter: UserRouter = wire[UserRouter]\n  $task\n}"),
        Fixtures.src("user/routers/UserRouter.scala", s"package app.user.routers\nclass UserRouter($params)")
      )
    )
    val text = Mermaid.render(
      ArchDiff.compare(
        model("a: UserService", "val analyze = Task.oneTime(TaskDescriptor[Int](\"analyze\"))(_ => IO.unit)"),
        model("a: UserService, b: UserService", "val analyze = Task.custom(TaskDescriptor[Int](\"analyze\"))(_ => IO.unit)"),
        "b",
        "h"
      )
    )
    assert(text.contains("- user: `UserRouter` now takes `UserService`"), text)
    assert(text.contains("- changed `analyze`: custom, in user; was one-time"), text)
  }

  test("an output longer than GitHub accepts replaces the lists under the diagram with a pointer to the HTML") {
    val migrations =
      (1 to 1500).map(i => Fixtures.migration(f"V$i%04d__create_a_table_with_a_rather_long_name_$i.sql", s"create table t$i (id int);")).toList
    val files = List(
      Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
      Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule")
    )
    val text = Mermaid.render(
      ArchDiff.compare(
        Extractor.extract(Fixtures.projectWithMigrations(files, Nil)),
        Extractor.extract(Fixtures.projectWithMigrations(files, migrations)),
        "b",
        "h"
      )
    )
    assert(text.length < 65536, text.length.toString)
    assert(text.contains("Open the HTML for them."), text.take(500))
  }

  test("an output longer than GitHub accepts still lists the new warnings and unclassified items, capped") {
    val publishers              = (1 to 700).map(i => s"class P$i(bus: EventBus[Payload$i]) { def p = bus.publish }").mkString("\n")
    def project(extra: Boolean) = Extractor.extract(
      Fixtures.project(
        List(
          Fixtures.src("main/ApplicationLoader.scala", "package app.main\nclass ApplicationLoader extends UserModule"),
          Fixtures.src("user/UserModule.scala", "package app.user\ntrait UserModule")
        ) ++ Option.when(extra)(Fixtures.src("user/services/Publishers.scala", s"package app.user.services\n$publishers")).toList*
      )
    )
    val text = Mermaid.render(ArchDiff.compare(project(extra = false), project(extra = true), "b", "h"))
    assert(text.length < 65536, text.length.toString)
    assert(text.contains("**New unclassified**"), text.take(800))
    assert(text.contains("+600 more"), text.takeRight(800))
  }
}
