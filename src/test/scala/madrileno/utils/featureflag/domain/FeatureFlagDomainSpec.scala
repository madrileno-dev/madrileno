package madrileno.utils.featureflag.domain

import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FeatureFlagDomainSpec extends AnyWordSpec with Matchers {

  "FlagKey.validate" should {
    "accept dotted lower-case keys and trim surrounding whitespace" in {
      FlagKey.validate("  checkout.new-flow_v2  ").map(_.unwrap) shouldBe Right("checkout.new-flow_v2")
    }

    "reject an empty key" in {
      FlagKey.validate("   ") shouldBe Left("FlagKey must be 1-128 chars")
    }

    "reject a key longer than 128 characters" in {
      FlagKey.validate("a" * 129) shouldBe Left("FlagKey must be 1-128 chars")
    }

    "reject keys that do not match the pattern" in {
      FlagKey.validate("Checkout") shouldBe Left("FlagKey must match [a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*")
      FlagKey.validate("1abc").isLeft shouldBe true
      FlagKey.validate("a..b").isLeft shouldBe true
      FlagKey.validate("a.").isLeft shouldBe true
    }
  }

  "FlagDescription.validate" should {
    "accept an empty description" in {
      FlagDescription.validate("").map(_.unwrap) shouldBe Right("")
    }

    "trim and accept up to 500 characters" in {
      FlagDescription.validate(" " + "x" * 500 + " ").map(_.unwrap) shouldBe Right("x" * 500)
    }

    "reject more than 500 characters" in {
      FlagDescription.validate("x" * 501) shouldBe Left("FlagDescription must be at most 500 chars")
    }
  }

  "FlagVariant.fromJson" should {
    "build each variant from matching JSON" in {
      FlagVariant.fromJson(VariantType.Boolean, Json.True) shouldBe Right(FlagVariant.BoolVariant(true))
      FlagVariant.fromJson(VariantType.String, Json.fromString("s")) shouldBe Right(FlagVariant.StringVariant("s"))
      FlagVariant.fromJson(VariantType.Int, Json.fromInt(7)) shouldBe Right(FlagVariant.IntVariant(7))
      FlagVariant.fromJson(VariantType.Json, Json.obj("a" -> Json.fromInt(1))) shouldBe Right(
        FlagVariant.JsonVariant(Json.obj("a" -> Json.fromInt(1)))
      )
    }

    "reject JSON of the wrong shape and echo the offending value" in {
      FlagVariant.fromJson(VariantType.Boolean, Json.fromString("yes")) shouldBe Left("expected boolean, got: \"yes\"")
      FlagVariant.fromJson(VariantType.String, Json.fromInt(1)) shouldBe Left("expected string, got: 1")
      FlagVariant.fromJson(VariantType.Int, Json.fromString("1")) shouldBe Left("expected int, got: \"1\"")
    }

    "reject a non-integral number for an Int variant" in {
      FlagVariant.fromJson(VariantType.Int, Json.fromDoubleOrNull(1.5)) shouldBe Left("expected int, got: 1.5")
      FlagVariant.fromJson(VariantType.Int, Json.fromLong(Long.MaxValue)).isLeft shouldBe true
    }
  }

  "FlagVariant" should {
    "round-trip through toJson" in {
      val variants =
        List(FlagVariant.BoolVariant(false), FlagVariant.StringVariant("v"), FlagVariant.IntVariant(-3), FlagVariant.JsonVariant(Json.Null))
      variants.map(v => FlagVariant.fromJson(v.variantType, v.toJson)) shouldBe variants.map(Right(_))
    }
  }
}
