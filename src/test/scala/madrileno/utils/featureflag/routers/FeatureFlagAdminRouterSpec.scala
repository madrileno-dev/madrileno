package madrileno.utils.featureflag.routers

import io.circe.Json
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.routers.dto.*
import madrileno.utils.featureflag.services.*
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.given
import madrileno.utils.pagination.{Page, SortDirection}
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.scalatest.OptionValues.*
import pl.iterators.baklava.{EmptyBody, FreeFormSchema, HttpBasic, Schema, SecurityScheme}
import pl.iterators.stir.server.Route

class FeatureFlagAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  // FlagVariant's JsonVariant payload (and EvaluationResultDto.value) is arbitrary JSON, and the rule ADTs are
  // sum types with payloads — neither has a derivable OpenAPI shape, so they're documented as free-form objects
  private given Schema[Json]          = FreeFormSchema("FlagValue")
  private given Schema[FlagVariant]   = FreeFormSchema("FlagVariant")
  private given Schema[RuleCondition] = FreeFormSchema("RuleCondition")
  private given Schema[RuleOutcome]   = FreeFormSchema("RuleOutcome")
  // pinned so AuditEntryDto's Option[FeatureFlagDto] fields don't re-derive it past the inline depth limit
  private given Schema[FeatureFlagDto] = Schema.autoDerived[FeatureFlagDto]

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  private val actor = Actor("router-spec")

  private def seedFlag(
    key: String,
    enabled: Boolean = true,
    defaultValue: FlagVariant = FlagVariant.BoolVariant(true),
    clientExposed: Boolean = false,
    rules: List[RuleData] = Nil
  ): FeatureFlag = {
    val command = CreateFlagCommand(FlagKey(key), FlagDescription(""), enabled, defaultValue, clientExposed, rules, actor)
    application.featureFlagService.createFlag(command).unsafeRunSync() match {
      case CreateFlagResult.Created(created) => created
      case other                             => fail(s"seeding '$key' failed: $other")
    }
  }

  private def seedSegment(name: String): Segment =
    application.featureFlagService.createSegment(CreateSegmentCommand(SegmentName(name), FlagDescription(""), Nil)).unsafeRunSync() match {
      case CreateSegmentResult.Created(created) => created
      case other                                => fail(s"seeding segment '$name' failed: $other")
    }

  private def sampleCreateRequest(key: String) =
    CreateFlagRequest(
      key = FlagKey(key),
      description = FlagDescription("created by router spec"),
      enabled = true,
      defaultValue = FlagVariant.BoolVariant(true),
      clientExposed = false,
      rules = List(
        RuleRequest(
          position = RulePosition(0),
          description = FlagDescription("enterprise only"),
          conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")),
          outcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(false))
        )
      )
    )

  private def sampleUpdateRequest(enabled: Boolean = true) =
    UpdateFlagRequest(
      description = FlagDescription("updated by router spec"),
      enabled = enabled,
      defaultValue = FlagVariant.BoolVariant(true),
      clientExposed = true,
      rules = Nil
    )

  path("/admin/feature-flags")(
    supports(
      GET,
      description = "List all feature flags with their rules.",
      summary = "List feature flags",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      withSetup {
        seedFlag("admin-list")
      }.request(_ => onRequest(security = basic.apply(adminUser, adminPass)))
        .respondsWith[List[FeatureFlagDto]](Ok, description = "All flags, sorted by key")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.map(_.key) should contain(seeded.key)
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    ),
    supports(
      POST,
      description = "Create a feature flag. The key is immutable; rules are validated against the flag's variant type.",
      summary = "Create a feature flag",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass), body = sampleCreateRequest("admin-create"))
        .respondsWith[FeatureFlagDto](Created, description = "Flag created")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.key shouldBe FlagKey("admin-create")
          response.body.rules should have size 1
        },
      withSetup {
        seedFlag("admin-create-dup")
      }.request(_ => onRequest(security = basic.apply(adminUser, adminPass), body = sampleCreateRequest("admin-create-dup")))
        .respondsWith[Error[Unit]](Conflict, description = "Key already taken")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("already exists")
        },
      onRequest(
        security = basic.apply(adminUser, adminPass),
        body = sampleCreateRequest("admin-create-invalid").copy(rules =
          List(
            RuleRequest(RulePosition(0), FlagDescription(""), Nil, RuleOutcome.FixedValue(FlagVariant.BoolVariant(false))),
            RuleRequest(RulePosition(0), FlagDescription(""), Nil, RuleOutcome.FixedValue(FlagVariant.BoolVariant(true)))
          )
        )
      )
        .respondsWith[Error[Unit]](BadRequest, description = "Duplicate rule positions or variant-type mismatch")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("duplicate rule positions")
        }
    )
  )

  path("/admin/feature-flags/{key}")(
    supports(
      GET,
      description = "Fetch one feature flag by key.",
      summary = "Get a feature flag",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedFlag("admin-get")
      }.request(flag => onRequest(security = basic.apply(adminUser, adminPass), pathParameters = flag.key.unwrap))
        .respondsWith[FeatureFlagDto](Ok, description = "Flag exists")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.key shouldBe seeded.key
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "admin-get-missing")
        .respondsWith[Error[Unit]](NotFound, description = "No such flag")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("No flag named")
        }
    ),
    supports(
      PUT,
      description =
        "Replace a flag's mutable fields (description, enabled, default value, client exposure, rules). Key, id and createdAt are preserved.",
      summary = "Update a feature flag",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedFlag("admin-update")
      }.request(flag =>
        onRequest(security = basic.apply(adminUser, adminPass), pathParameters = flag.key.unwrap, body = sampleUpdateRequest(enabled = false))
      ).respondsWith[FeatureFlagDto](Ok, description = "Flag updated")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe seeded.id
          response.body.enabled shouldBe false
          response.body.clientExposed shouldBe true
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "admin-update-missing", body = sampleUpdateRequest())
        .respondsWith[Error[Unit]](NotFound, description = "No such flag")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("No flag named")
        }
    ),
    supports(
      DELETE,
      description = "Delete a flag and its rules. Audit entries survive with the flag reference detached.",
      summary = "Delete a feature flag",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedFlag("admin-delete")
      }.request(flag => onRequest(security = basic.apply(adminUser, adminPass), pathParameters = flag.key.unwrap))
        .respondsWith[EmptyBody](NoContent, description = "Flag deleted")
        .assert { case (ctx, _) => ctx.performRequest(allRoutes) },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "admin-delete-missing")
        .respondsWith[Error[Unit]](NotFound, description = "No such flag")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/admin/feature-flags/{key}/toggle")(
    supports(
      POST,
      description = "Enable or disable a flag without touching its rules.",
      summary = "Toggle a feature flag",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedFlag("admin-toggle")
      }.request(flag =>
        onRequest(security = basic.apply(adminUser, adminPass), pathParameters = flag.key.unwrap, body = ToggleFlagRequest(enabled = false))
      ).respondsWith[FeatureFlagDto](Ok, description = "Flag toggled")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.enabled shouldBe false
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "admin-toggle-missing", body = ToggleFlagRequest(enabled = false))
        .respondsWith[Error[Unit]](NotFound, description = "No such flag")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/admin/feature-flags/{key}/audit")(
    supports(
      GET,
      description = "Audit trail for a flag (paginated, newest first by default). Entries survive flag deletion.",
      summary = "List a flag's audit entries",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      queryParameters = (
        q[Option[AuditSortField]]("sort-by", "Sort field — CreatedAt (default)"),
        q[Option[SortDirection]]("sort-dir", "Sort direction — Asc | Desc (default Desc)"),
        q[Option[Int]]("limit", "Page size, 1–100 (default 20)"),
        q[Option[Int]]("offset", "Rows to skip (default 0)")
      ),
      tags = Seq("Admin")
    )(
      withSetup {
        val flag = seedFlag("admin-audit")
        val _    = application.featureFlagService.toggleFlag(ToggleFlagCommand(flag.key, enabled = false, actor)).unsafeRunSync()
        flag
      }.request(flag =>
        onRequest(
          security = basic.apply(adminUser, adminPass),
          pathParameters = flag.key.unwrap,
          queryParameters = (Option.empty[AuditSortField], Option.empty[SortDirection], Option.empty[Int], Option.empty[Int])
        )
      ).respondsWith[Page[AuditEntryDto]](Ok, description = "A page of audit entries")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.total shouldBe 2L
          response.body.items.map(_.action) should contain theSameElementsAs List(AuditAction.Created, AuditAction.Toggled)
          response.body.items.map(_.flagKey).toSet shouldBe Set(seeded.key)
        }
    )
  )

  path("/admin/feature-flags/{key}/evaluate")(
    supports(
      POST,
      description = "Dry-run a flag against an arbitrary evaluation context, bypassing caches. For debugging targeting rules.",
      summary = "Evaluate a flag for a given context",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("key"),
      tags = Seq("Admin")
    )(
      withSetup {
        val rule = RuleData(
          RulePosition(0),
          FlagDescription(""),
          conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")),
          outcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(false))
        )
        seedFlag("admin-evaluate", rules = List(rule))
      }.request(flag =>
        onRequest(
          security = basic.apply(adminUser, adminPass),
          pathParameters = flag.key.unwrap,
          body = EvaluateFlagRequest(TargetingKey("user-1"), Map("plan" -> "enterprise"))
        )
      ).respondsWith[EvaluationResultDto](Ok, description = "Evaluation result with reason")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.value shouldBe Json.False
          response.body.reason shouldBe EvaluationReason.TargetingMatch
        },
      onRequest(
        security = basic.apply(adminUser, adminPass),
        pathParameters = "admin-evaluate-missing",
        body = EvaluateFlagRequest(TargetingKey("user-1"), Map.empty)
      )
        .respondsWith[Error[Unit]](NotFound, description = "No such flag")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/admin/feature-flag-segments")(
    supports(GET, description = "List all segments.", summary = "List segments", securitySchemes = Seq(basicScheme), tags = Seq("Admin"))(
      withSetup {
        seedSegment("admin-segment-list")
      }.request(_ => onRequest(security = basic.apply(adminUser, adminPass)))
        .respondsWith[List[SegmentDto]](Ok, description = "All segments")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.map(_.name) should contain(seeded.name)
        }
    ),
    supports(
      POST,
      description = "Create a reusable segment referenced from rules via SegmentMatch.",
      summary = "Create a segment",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(
        security = basic.apply(adminUser, adminPass),
        body = CreateSegmentRequest(
          name = SegmentName("admin-segment-create"),
          description = FlagDescription("beta users"),
          conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "beta"))
        )
      )
        .respondsWith[SegmentDto](Created, description = "Segment created")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name shouldBe SegmentName("admin-segment-create")
        },
      withSetup {
        seedSegment("admin-segment-dup")
      }.request(segment =>
        onRequest(
          security = basic.apply(adminUser, adminPass),
          body = CreateSegmentRequest(name = segment.name, description = FlagDescription(""), conditions = Nil)
        )
      ).respondsWith[Error[Unit]](Conflict, description = "Name already taken")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("already exists")
        }
    )
  )

  path("/admin/feature-flag-segments/{name}")(
    supports(
      PUT,
      description = "Replace a segment's description and conditions. Name, id and createdAt are preserved.",
      summary = "Update a segment",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("name"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedSegment("admin-segment-update")
      }.request(segment =>
        onRequest(
          security = basic.apply(adminUser, adminPass),
          pathParameters = segment.name.unwrap,
          body = UpdateSegmentRequest(FlagDescription("now enterprise"), List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
        )
      ).respondsWith[SegmentDto](Ok, description = "Segment updated")
        .assert { case (ctx, seeded) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe seeded.id
          response.body.description shouldBe FlagDescription("now enterprise")
        },
      onRequest(
        security = basic.apply(adminUser, adminPass),
        pathParameters = "admin-segment-update-missing",
        body = UpdateSegmentRequest(FlagDescription(""), Nil)
      )
        .respondsWith[Error[Unit]](NotFound, description = "No such segment")
        .assert(_.performRequest(allRoutes))
    ),
    supports(
      DELETE,
      description = "Delete a segment. Rules still referencing it stop matching (missing segment never matches).",
      summary = "Delete a segment",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("name"),
      tags = Seq("Admin")
    )(
      withSetup {
        seedSegment("admin-segment-delete")
      }.request(segment => onRequest(security = basic.apply(adminUser, adminPass), pathParameters = segment.name.unwrap))
        .respondsWith[EmptyBody](NoContent, description = "Segment deleted")
        .assert { case (ctx, _) => ctx.performRequest(allRoutes) },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "admin-segment-delete-missing")
        .respondsWith[Error[Unit]](NotFound, description = "No such segment")
        .assert(_.performRequest(allRoutes))
    )
  )
}
