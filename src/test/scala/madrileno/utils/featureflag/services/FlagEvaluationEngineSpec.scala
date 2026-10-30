package madrileno.utils.featureflag.services

import io.circe.Json
import madrileno.support.TestData
import madrileno.utils.featureflag.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FlagEvaluationEngineSpec extends AnyWordSpec with Matchers {

  private val anon = EvaluationContext(TargetingKey("user-1"))

  private def ctxWith(attrs: (String, String)*): EvaluationContext =
    EvaluationContext(TargetingKey("user-1"), attrs.map { case (k, v) => AttributeName(k) -> AttributeValue(v) }.toMap)

  private def evaluate(
    flag: FeatureFlag,
    ctx: EvaluationContext,
    segments: List[Segment] = Nil
  ): FlagEvaluationEngine.Result =
    FlagEvaluationEngine.evaluate(flag, segments.map(s => s.name -> s).toMap, ctx)

  "FlagEvaluationEngine.evaluate" should {
    "return defaultValue with Default reason when the flag is enabled and no rules" in {
      val f      = TestData.featureFlag(defaultValue = FlagVariant.BoolVariant(true))
      val result = evaluate(f, anon)
      result.value shouldBe FlagVariant.BoolVariant(true)
      result.reason shouldBe EvaluationReason.Default
    }

    "return defaultValue with Disabled reason when the flag is disabled (even with rules)" in {
      val matching = TestData.flagRule(conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
      val f        = TestData.featureFlag(enabled = false, rules = List(matching))
      val result   = evaluate(f, ctxWith("plan" -> "enterprise"))
      result.value shouldBe FlagVariant.BoolVariant(false)
      result.reason shouldBe EvaluationReason.Disabled
    }

    "preserve the variant for all four types" in {
      val cases: List[FlagVariant] = List(
        FlagVariant.BoolVariant(true),
        FlagVariant.StringVariant("hello"),
        FlagVariant.IntVariant(42),
        FlagVariant.JsonVariant(Json.obj("k" -> Json.fromString("v")))
      )
      cases.foreach { value =>
        val f = TestData.featureFlag(defaultValue = value)
        evaluate(f, anon).value shouldBe value
      }
    }
  }

  "rule conditions" should {
    def withCondition(c: RuleCondition): FeatureFlag =
      TestData.featureFlag(rules = List(TestData.flagRule(conditions = List(c))))

    "StringEquals matches exact" in {
      val f = withCondition(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"))
      evaluate(f, ctxWith("plan" -> "enterprise")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("plan" -> "free")).reason shouldBe EvaluationReason.Default
    }

    "StringIn matches set membership" in {
      val f = withCondition(RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1", "eu-west-2")))
      evaluate(f, ctxWith("region" -> "eu-west-2")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("region" -> "us-east-1")).reason shouldBe EvaluationReason.Default
    }

    "StringContains and StringStartsWith match substrings" in {
      val containsF   = withCondition(RuleCondition.StringContains(AttributeName("ua"), "Firefox"))
      val startsWithF = withCondition(RuleCondition.StringStartsWith(AttributeName("ua"), "Mozilla"))
      evaluate(containsF, ctxWith("ua" -> "Mozilla/5.0 Firefox/120")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(containsF, ctxWith("ua" -> "Mozilla/5.0 Chrome/100")).reason shouldBe EvaluationReason.Default
      evaluate(startsWithF, ctxWith("ua" -> "Mozilla/5.0 Firefox/120")).reason shouldBe EvaluationReason.TargetingMatch
    }

    "Int operators parse the attribute string and compare" in {
      val eqF = withCondition(RuleCondition.IntEquals(AttributeName("age"), 18))
      val gtF = withCondition(RuleCondition.IntGreaterThan(AttributeName("age"), 21))
      val ltF = withCondition(RuleCondition.IntLessThan(AttributeName("age"), 65))
      evaluate(eqF, ctxWith("age" -> "18")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(gtF, ctxWith("age" -> "30")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(gtF, ctxWith("age" -> "15")).reason shouldBe EvaluationReason.Default
      evaluate(ltF, ctxWith("age" -> "100")).reason shouldBe EvaluationReason.Default
    }

    "BoolEquals parses the attribute string" in {
      val f = withCondition(RuleCondition.BoolEquals(AttributeName("verified"), true))
      evaluate(f, ctxWith("verified" -> "true")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("verified" -> "false")).reason shouldBe EvaluationReason.Default
    }

    "all conditions in a rule must match (AND within rule)" in {
      val f = TestData.featureFlag(rules =
        List(
          TestData.flagRule(conditions =
            List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"), RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1")))
          )
        )
      )
      evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "eu-west-1")).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "us-east-1")).reason shouldBe EvaluationReason.Default
      evaluate(f, ctxWith("plan" -> "free", "region" -> "eu-west-1")).reason shouldBe EvaluationReason.Default
    }

    "first matching rule wins (rules evaluated in position order)" in {
      val ruleA = TestData.flagRule(
        position = RulePosition(0),
        conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")),
        outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("variant-a"))
      )
      val ruleB = TestData.flagRule(
        position = RulePosition(1),
        conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")),
        outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("variant-b"))
      )
      val f = TestData.featureFlag(defaultValue = FlagVariant.StringVariant("default"), rules = List(ruleA, ruleB))
      evaluate(f, ctxWith("plan" -> "enterprise")).value shouldBe FlagVariant.StringVariant("variant-a")
    }
  }

  "segment matching" should {
    def flagMatchingSegment(name: String): FeatureFlag =
      TestData.featureFlag(rules = List(TestData.flagRule(conditions = List(RuleCondition.SegmentMatch(SegmentName(name))))))

    "match when the named segment's conditions hold" in {
      val segment = TestData.flagSegment(
        name = SegmentName("enterprise-eu"),
        conditions =
          List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"), RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1")))
      )
      val f = flagMatchingSegment("enterprise-eu")
      evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "eu-west-1"), List(segment)).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("plan" -> "free", "region" -> "eu-west-1"), List(segment)).reason shouldBe EvaluationReason.Default
    }

    "never match a missing segment, even when the caller forges sentinel-like attributes" in {
      val f = flagMatchingSegment("deleted-segment")
      evaluate(f, anon).reason shouldBe EvaluationReason.Default
      evaluate(f, ctxWith("__segment_missing__" -> "true")).reason shouldBe EvaluationReason.Default
      evaluate(f, ctxWith("deleted-segment" -> "true")).reason shouldBe EvaluationReason.Default
    }

    "resolve segments nested inside segments" in {
      val eu = TestData.flagSegment(name = SegmentName("eu"), conditions = List(RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1"))))
      val enterpriseEu = TestData.flagSegment(
        name = SegmentName("enterprise-eu"),
        conditions = List(RuleCondition.SegmentMatch(SegmentName("eu")), RuleCondition.StringEquals(AttributeName("plan"), "enterprise"))
      )
      val f        = flagMatchingSegment("enterprise-eu")
      val segments = List(eu, enterpriseEu)
      evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "eu-west-1"), segments).reason shouldBe EvaluationReason.TargetingMatch
      evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "us-east-1"), segments).reason shouldBe EvaluationReason.Default
    }

    "never match a segment reference cycle" in {
      val a = TestData.flagSegment(name = SegmentName("seg-a"), conditions = List(RuleCondition.SegmentMatch(SegmentName("seg-b"))))
      val b = TestData.flagSegment(name = SegmentName("seg-b"), conditions = List(RuleCondition.SegmentMatch(SegmentName("seg-a"))))
      val f = flagMatchingSegment("seg-a")
      evaluate(f, anon, List(a, b)).reason shouldBe EvaluationReason.Default
    }
  }

  "PercentageRollout" should {
    "return Split reason for users in the bucket; Default otherwise" in {
      val r = TestData.flagRule(outcome = RuleOutcome.PercentageRollout(Percentage(50), RolloutSeed("phase2-test"), FlagVariant.BoolVariant(true)))
      val f = TestData.featureFlag(rules = List(r))
      val results = (1 to 200).map(i => evaluate(f, EvaluationContext(TargetingKey(s"user-$i"))).reason)
      val hits    = results.count(_ == EvaluationReason.Split)
      hits should (be >= 70 and be <= 130)
    }

    "be sticky — same user, same seed, same bucket — across invocations" in {
      val r = TestData.flagRule(outcome = RuleOutcome.PercentageRollout(Percentage(50), RolloutSeed("phase2-sticky"), FlagVariant.BoolVariant(true)))
      val f = TestData.featureFlag(rules = List(r))
      val ctx1 = EvaluationContext(TargetingKey("alice"))
      val r1   = evaluate(f, ctx1).reason
      val r2   = evaluate(f, ctx1).reason
      val r3   = evaluate(f, ctx1).reason
      r1 shouldBe r2
      r2 shouldBe r3
    }

    "Percentage(0) never matches; Percentage(100) always matches" in {
      val zero  = TestData.flagRule(outcome = RuleOutcome.PercentageRollout(Percentage(0), RolloutSeed("x"), FlagVariant.BoolVariant(true)))
      val full  = TestData.flagRule(outcome = RuleOutcome.PercentageRollout(Percentage(100), RolloutSeed("x"), FlagVariant.BoolVariant(true)))
      val fZero = TestData.featureFlag(rules = List(zero))
      val fFull = TestData.featureFlag(rules = List(full))
      (1 to 50).foreach { i =>
        val ctx = EvaluationContext(TargetingKey(s"u-$i"))
        evaluate(fZero, ctx).reason shouldBe EvaluationReason.Default
        evaluate(fFull, ctx).reason shouldBe EvaluationReason.Split
      }
    }
  }
}
