package madrileno.utils.featureflag.services

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestCacheRuntime, TestTransactor}
import madrileno.utils.events.bus.{EventBus, EventBusRuntime}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagAuditRepository, FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.pagination.PageRequest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

class FeatureFlagServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val eventBus: EventBus[FeatureFlagEvent] =
    EventBusRuntime.local.topic[FeatureFlagEvent]("feature_flag_events_test", maxQueued = 64)

  private val actor = Actor("spec-admin")

  private def newService: FeatureFlagServiceLive = {
    val ruleRepository = new RuleRepository
    new FeatureFlagServiceLive(
      new FeatureFlagRepository(ruleRepository),
      ruleRepository,
      new SegmentRepository,
      new FeatureFlagAuditRepository,
      TestCacheRuntime.unbounded,
      transactor,
      eventBus
    )
  }

  private lazy val service = newService

  private val ctx = EvaluationContext(TargetingKey("user-1"))

  private def ruleData(
    position: RulePosition = RulePosition(0),
    conditions: List[RuleCondition] = Nil,
    outcome: RuleOutcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(true))
  ): RuleData = RuleData(position, FlagDescription(""), conditions, outcome)

  private def seedFlag(
    key: String,
    enabled: Boolean = true,
    defaultValue: FlagVariant = FlagVariant.BoolVariant(true),
    clientExposed: Boolean = false,
    rules: List[RuleData] = Nil
  ): IO[FeatureFlag] = {
    val command = CreateFlagCommand(FlagKey(key), FlagDescription(""), enabled, defaultValue, clientExposed, rules, actor)
    service.createFlag(command).flatMap {
      case CreateFlagResult.Created(created) => IO.pure(created)
      case other                             => IO.raiseError(new IllegalStateException(s"seeding '$key' failed: $other"))
    }
  }

  "FeatureFlagServiceLive" should {
    "return defaultValue for an enabled boolean flag" in {
      for {
        _      <- seedFlag("phase1-bool-on")
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-on"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Default
      }
    }

    "return defaultValue with Disabled when the flag is disabled" in {
      for {
        _      <- seedFlag("phase1-bool-off", enabled = false)
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-off"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Disabled
      }
    }

    "return the caller default with EvaluationErrorCode.FlagNotFound when the flag does not exist" in {
      service
        .evaluator(ctx)
        .booleanDetail(FlagKey("phase1-missing"), default = false)
        .asserting { result =>
          result.value shouldBe false
          result.reason shouldBe EvaluationReason.Error
          result.errorCode shouldBe Some(EvaluationErrorCode.FlagNotFound)
        }
    }

    "return EvaluationErrorCode.TypeMismatch when the flag's variant doesn't match the caller's typed call" in {
      for {
        _      <- seedFlag("phase1-string-flag", defaultValue = FlagVariant.StringVariant("v3"))
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-string-flag"), default = false)
      } yield {
        result.value shouldBe false
        result.reason shouldBe EvaluationReason.Error
        result.errorCode shouldBe Some(EvaluationErrorCode.TypeMismatch)
      }
    }

    "return EvaluationErrorCode.TypeMismatch when calling evaluateJson on a non-Json flag" in {
      for {
        _      <- seedFlag("phase1-bool-as-json")
        result <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-bool-as-json"), default = Json.Null)
      } yield {
        result.value shouldBe Json.Null
        result.reason shouldBe EvaluationReason.Error
        result.errorCode shouldBe Some(EvaluationErrorCode.TypeMismatch)
      }
    }

    "support int and json variant types" in {
      for {
        _ <- seedFlag("phase1-int", defaultValue = FlagVariant.IntVariant(42))
        _ <- seedFlag("phase1-json", defaultValue = FlagVariant.JsonVariant(Json.obj("k" -> Json.fromString("v"))))
        i <- service.evaluator(ctx).intDetail(FlagKey("phase1-int"), default = 0)
        j <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-json"), default = Json.Null)
      } yield {
        i.value shouldBe 42
        j.value shouldBe Json.obj("k" -> Json.fromString("v"))
      }
    }

    "persist rules through createFlag and match them on evaluation" in {
      val enterpriseRule = ruleData(conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
      val ctxEnterprise  = EvaluationContext(TargetingKey("user-x"), Map(AttributeName("plan") -> AttributeValue("enterprise")))
      val ctxFree        = EvaluationContext(TargetingKey("user-y"), Map(AttributeName("plan") -> AttributeValue("free")))
      for {
        _   <- seedFlag("phase2-rule", defaultValue = FlagVariant.BoolVariant(false), rules = List(enterpriseRule))
        ent <- service.evaluator(ctxEnterprise).booleanDetail(FlagKey("phase2-rule"), default = false)
        fr  <- service.evaluator(ctxFree).booleanDetail(FlagKey("phase2-rule"), default = false)
      } yield {
        ent.value shouldBe true
        ent.reason shouldBe EvaluationReason.TargetingMatch
        fr.value shouldBe false
        fr.reason shouldBe EvaluationReason.Default
      }
    }

    "update an existing flag through updateFlag and see the change immediately on the writing instance" in {
      val key = FlagKey("phase2-toggle")
      for {
        _     <- seedFlag(key.unwrap, enabled = true)
        first <- service.evaluator(ctx).booleanDetail(key, default = false)
        _     <- service.updateFlag(
               UpdateFlagCommand(key, FlagDescription(""), enabled = false, FlagVariant.BoolVariant(true), clientExposed = false, Nil, actor)
             )
        afterInv <- service.evaluator(ctx).booleanDetail(key, default = false)
      } yield {
        first.reason shouldBe EvaluationReason.Default
        afterInv.reason shouldBe EvaluationReason.Disabled
      }
    }

    "replace rules on updateFlag instead of accumulating them" in {
      val key   = FlagKey("phase2-rule-replace")
      val ruleA = ruleData(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("a")))
      val ruleB = ruleData(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("b")))
      for {
        _ <- seedFlag(key.unwrap, defaultValue = FlagVariant.StringVariant("default"), rules = List(ruleA))
        _ <- service.updateFlag(
               UpdateFlagCommand(
                 key,
                 FlagDescription(""),
                 enabled = true,
                 FlagVariant.StringVariant("default"),
                 clientExposed = false,
                 List(ruleB),
                 actor
               )
             )
        result <- service.evaluator(ctx).stringDetail(key, default = "caller-default")
      } yield result.value shouldBe "b"
    }

    "propagate invalidation to other service instances via the event bus" in {
      val key    = FlagKey("phase2-cross-instance")
      val reader = newService
      // republishing compensates for events published before the reader's background subscription was live
      def pollUntilDisabled(attempts: Int): IO[EvaluationDetail[Boolean]] =
        reader.evaluator(ctx).booleanDetail(key, default = false).flatMap { detail =>
          if (detail.reason == EvaluationReason.Disabled || attempts <= 0) IO.pure(detail)
          else eventBus.publish(FeatureFlagEvent.Invalidated(key)) *> IO.sleep(50.millis) *> pollUntilDisabled(attempts - 1)
        }
      reader.invalidationLifecycle.use { _ =>
        for {
          _          <- seedFlag(key.unwrap, enabled = true)
          first      <- reader.evaluator(ctx).booleanDetail(key, default = false)
          _          <- service.toggleFlag(ToggleFlagCommand(key, enabled = false, actor))
          afterEvent <- pollUntilDisabled(attempts = 100)
        } yield {
          first.reason shouldBe EvaluationReason.Default
          afterEvent.reason shouldBe EvaluationReason.Disabled
        }
      }
    }

    "reject createFlag when rules share a position" in {
      val command = CreateFlagCommand(
        FlagKey("phase2-dup-positions"),
        FlagDescription(""),
        enabled = true,
        FlagVariant.BoolVariant(true),
        clientExposed = false,
        List(ruleData(position = RulePosition(0)), ruleData(position = RulePosition(0))),
        actor
      )
      service.createFlag(command).asserting(_ shouldBe a[CreateFlagResult.Invalid])
    }

    "reject createFlag when a rule outcome's variant type doesn't match the flag's" in {
      val command = CreateFlagCommand(
        FlagKey("phase2-mismatched"),
        FlagDescription(""),
        enabled = true,
        FlagVariant.BoolVariant(false),
        clientExposed = false,
        List(ruleData(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("oops")))),
        actor
      )
      service.createFlag(command).asserting(_ shouldBe a[CreateFlagResult.Invalid])
    }

    "evaluate segment-driven rules and pick up segment changes" in {
      val key         = FlagKey("phase2-segment")
      val segmentName = SegmentName("phase2-beta")
      val rule        = ruleData(conditions = List(RuleCondition.SegmentMatch(segmentName)))
      val ctxBeta     = EvaluationContext(TargetingKey("user-b"), Map(AttributeName("plan") -> AttributeValue("beta")))
      for {
        _ <- service.createSegment(
               CreateSegmentCommand(segmentName, FlagDescription(""), List(RuleCondition.StringEquals(AttributeName("plan"), "beta")))
             )
        _      <- seedFlag(key.unwrap, defaultValue = FlagVariant.BoolVariant(false), rules = List(rule))
        before <- service.evaluator(ctxBeta).booleanDetail(key, default = false)
        _      <- service.updateSegment(
               UpdateSegmentCommand(segmentName, FlagDescription(""), List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
             )
        after <- service.evaluator(ctxBeta).booleanDetail(key, default = false)
      } yield {
        before.reason shouldBe EvaluationReason.TargetingMatch
        after.reason shouldBe EvaluationReason.Default
      }
    }

    "return KeyExists when creating a flag with an already-used key" in {
      for {
        _      <- seedFlag("phase3-dup-key")
        result <- service.createFlag(
                    CreateFlagCommand(
                      FlagKey("phase3-dup-key"),
                      FlagDescription(""),
                      enabled = true,
                      FlagVariant.BoolVariant(true),
                      clientExposed = false,
                      Nil,
                      actor
                    )
                  )
      } yield result shouldBe CreateFlagResult.KeyExists
    }

    "return NotFound when updating, toggling or deleting a missing flag" in {
      val key = FlagKey("phase3-missing-admin")
      for {
        updated <- service.updateFlag(
                     UpdateFlagCommand(key, FlagDescription(""), enabled = true, FlagVariant.BoolVariant(true), clientExposed = false, Nil, actor)
                   )
        toggled <- service.toggleFlag(ToggleFlagCommand(key, enabled = false, actor))
        deleted <- service.deleteFlag(DeleteFlagCommand(key, actor))
      } yield {
        updated shouldBe UpdateFlagResult.NotFound
        toggled shouldBe UpdateFlagResult.NotFound
        deleted shouldBe DeleteFlagResult.NotFound
      }
    }

    "preserve id, key and createdAt across updateFlag" in {
      val key = FlagKey("phase3-immutable-fields")
      for {
        created <- seedFlag(key.unwrap)
        result  <-
          service.updateFlag(
            UpdateFlagCommand(key, FlagDescription("changed"), enabled = true, FlagVariant.BoolVariant(true), clientExposed = false, Nil, actor)
          )
        updated = result match {
                    case UpdateFlagResult.Updated(flag) => flag
                    case other                          => fail(s"expected Updated, got $other")
                  }
      } yield {
        updated.id shouldBe created.id
        updated.key shouldBe created.key
        // createdAt round-trips through timestamptz, which truncates Instant nanos to micros
        updated.createdAt shouldBe created.createdAt.truncatedTo(ChronoUnit.MICROS)
        updated.description shouldBe FlagDescription("changed")
      }
    }

    "record an audit trail across create, update, toggle and delete" in {
      val key = FlagKey("phase3-audit")
      for {
        _ <- seedFlag(key.unwrap)
        _ <- service.updateFlag(
               UpdateFlagCommand(key, FlagDescription("updated"), enabled = true, FlagVariant.BoolVariant(true), clientExposed = false, Nil, actor)
             )
        _      <- service.toggleFlag(ToggleFlagCommand(key, enabled = false, actor))
        _      <- service.deleteFlag(DeleteFlagCommand(key, actor))
        result <- service.listAudit(key, PageRequest.firstPageBy(AuditSortField.CreatedAt))
        (entries, total) = result
      } yield {
        total shouldBe 4L
        entries.map(_.action) should contain theSameElementsAs
          List(AuditAction.Created, AuditAction.Updated, AuditAction.Toggled, AuditAction.Deleted)
        all(entries.map(_.actor)) shouldBe actor
        val created = entries.find(_.action == AuditAction.Created).get
        created.before shouldBe None
        created.after.map(_.key) shouldBe Some(key)
        val deleted = entries.find(_.action == AuditAction.Deleted).get
        deleted.before.map(_.key) shouldBe Some(key)
        deleted.after shouldBe None
        // delete detaches the flag reference (ON DELETE SET NULL); the trail stays queryable by key
        all(entries.map(_.flagId)) shouldBe None
      }
    }

    "evaluate only client-exposed flags in evaluateClientExposed" in {
      for {
        _     <- seedFlag("phase3-exposed", clientExposed = true)
        _     <- seedFlag("phase3-hidden")
        flags <- service.evaluateClientExposed(ctx)
      } yield {
        flags.get(FlagKey("phase3-exposed")) shouldBe Some(Json.True)
        flags.get(FlagKey("phase3-hidden")) shouldBe None
      }
    }

    "return NameExists when creating a segment with an already-used name" in {
      val name = SegmentName("phase3-dup-segment")
      for {
        _      <- service.createSegment(CreateSegmentCommand(name, FlagDescription(""), Nil))
        result <- service.createSegment(CreateSegmentCommand(name, FlagDescription(""), Nil))
      } yield result shouldBe CreateSegmentResult.NameExists
    }

    "delete segments and report NotFound for missing ones" in {
      val name = SegmentName("phase3-delete-segment")
      for {
        _       <- service.createSegment(CreateSegmentCommand(name, FlagDescription(""), Nil))
        deleted <- service.deleteSegment(DeleteSegmentCommand(name))
        missing <- service.deleteSegment(DeleteSegmentCommand(name))
      } yield {
        deleted shouldBe DeleteSegmentResult.Deleted
        missing shouldBe DeleteSegmentResult.NotFound
      }
    }
  }
}
