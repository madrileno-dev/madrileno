package __package__.__aggregate__.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import __package__.__aggregate__.domain.*
import __package__.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class __Aggregate__RepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo = new __Aggregate__Repository
  private val now       = Instant.parse("2026-01-01T10:00:00Z")

  private def make__Aggregate__(name: __Aggregate__Name = __Aggregate__Name("test-name")): __Aggregate__ =
    __Aggregate__.create(TestData.random__Aggregate__Id(), name, now)

  "__Aggregate__Repository" should {
    "save and find a __aggregate__" in withRollback {
      val entity = make__Aggregate__()
      for {
        _       <- repo.save(entity)
        fetched <- repo.find(entity.id)
      } yield fetched shouldBe Some(entity)
    }

    "find returns None for a non-existent __aggregate__" in withRollback {
      repo.find(TestData.random__Aggregate__Id()).map(_ shouldBe None)
    }

    "update applies the transition and persists the new aggregate" in withRollback {
      val entity = make__Aggregate__()
      val later  = now.plusSeconds(60)
      for {
        _       <- repo.save(entity)
        result  <- repo.update(entity.id, _.rename(__Aggregate__Name("updated"), later))
        fetched <- repo.find(entity.id)
      } yield {
        result.map(_.map(_.name)) shouldBe Some(Right(__Aggregate__Name("updated")))
        fetched.map(_.name) shouldBe Some(__Aggregate__Name("updated"))
        fetched.map(_.updatedAt) shouldBe Some(later)
      }
    }

    "update returns the rejection without persisting" in withRollback {
      val entity = make__Aggregate__()
      for {
        _       <- repo.save(entity)
        result  <- repo.update(entity.id, _.rename(entity.name, now.plusSeconds(60)))
        fetched <- repo.find(entity.id)
      } yield {
        result shouldBe Some(Left(RenameRejection.NameUnchanged))
        fetched.map(_.updatedAt) shouldBe Some(now)
      }
    }

    "update returns None for a non-existent __aggregate__" in withRollback {
      repo
        .update(TestData.random__Aggregate__Id(), (a: __Aggregate__) => a.rename(__Aggregate__Name("x"), now))
        .map(_ shouldBe None)
    }

    "softDelete hides the __aggregate__ from find" in withRollback {
      val entity = make__Aggregate__()
      for {
        _           <- repo.save(entity)
        _           <- repo.softDelete(entity.id, now.plusSeconds(1))
        afterDelete <- repo.find(entity.id)
      } yield afterDelete shouldBe None
    }
  }
}
