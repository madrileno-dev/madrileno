package madrileno.utils.db.dsl

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.UUID

final case class ProbeRow(
  id: UUID,
  ownerId: UUID,
  name: String,
  score: Int,
  active: Boolean,
  note: Option[String],
  labels: List[String],
  deletedAt: Option[Instant])

object ProbeTable extends Table[ProbeRow]("dsl_probe") with IdTable[ProbeRow, UUID] with SoftDeleteTable with ForeignIdTable[UUID] {
  override val id: Column[UUID]                   = column("id", uuid)
  val ownerId: Column[UUID]                       = column("owner_id", uuid)
  val name: Column[String]                        = column("name", text)
  val score: Column[Int]                          = column("score", int4)
  val active: Column[Boolean]                     = column("active", bool)
  val note: Column[Option[String]]                = column("note", text.opt)
  val labels: Column[List[String]]                = column("labels", listOf(text))
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  override val foreignId: Column[UUID]            = ownerId

  override def mapping: (List[Column[?]], Codec[ProbeRow]) = (id, ownerId, name, score, active, note, labels, deletedAt)
}

final case class ProbeFilter(
  id: SqlPredicate[UUID] = p.any,
  ownerId: SqlPredicate[UUID] = p.any,
  name: SqlPredicate[String] = p.any,
  score: SqlPredicate[Int] = p.any,
  active: SqlPredicate[Boolean] = p.any,
  note: SqlPredicate[String] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(
      this,
      (ProbeTable.id, ProbeTable.ownerId, ProbeTable.name, ProbeTable.score, ProbeTable.active, ProbeTable.note, ProbeTable.deletedAt)
    )
}

class RepositoryDslSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val repo =
    new IdRepository[ProbeRow, UUID](_.id)
      with SoftDeleteRepository[ProbeRow, UUID]
      with ForeignIdRepository[ProbeRow, UUID]
      with FilteringRepository[ProbeRow, ProbeFilter] {
      override val table: ProbeTable.type = ProbeTable
    }

  private def createTable: IO[Unit] = withSession {
    summon[Session[IO]]
      .execute(sql"""CREATE TABLE IF NOT EXISTS dsl_probe (
        id UUID PRIMARY KEY,
        owner_id UUID NOT NULL,
        name TEXT NOT NULL,
        score INT NOT NULL,
        active BOOLEAN NOT NULL,
        note TEXT,
        labels TEXT[] NOT NULL,
        deleted_at TIMESTAMPTZ
      )""".command)
      .void
  }

  private val ownerA = TestData.randomUuid()
  private val ownerB = TestData.randomUuid()
  private val now    = Instant.parse("2026-10-13T12:00:00Z")

  private def row(
    name: String,
    score: Int,
    owner: UUID = ownerA,
    active: Boolean = true,
    note: Option[String] = None,
    labels: List[String] = Nil
  ): ProbeRow = ProbeRow(TestData.randomUuid(), owner, name, score, active, note, labels, None)

  private val alpha = row("alpha", 1, note = Some("first"), labels = List("x", "y"))
  private val beta  = row("beta", 2, active = false, note = Some("second"))
  private val gamma = row("gamma", 3, owner = ownerB, labels = List("z"))
  private val delta = row("Delta", 4, owner = ownerB, note = Some("fourth"))
  private val all   = List(alpha, beta, gamma, delta)

  private def seeded[A](f: Session[IO] ?=> IO[A]): IO[A] =
    createTable *> withRollback(repo.createAll(all) *> f)

  private def names(filter: ProbeFilter)(using Session[IO]): IO[List[String]] = repo.findByFilter(filter).map(_.map(_.name).sorted)

  "FilteringRepository predicates" should {
    "match on equality and inequality" in seeded {
      for {
        eq  <- names(ProbeFilter(name = p.equal("beta")))
        neq <- names(ProbeFilter(name = p.notEqual("beta")))
      } yield {
        eq shouldBe List("beta")
        neq shouldBe List("Delta", "alpha", "gamma")
      }
    }

    "match on membership, treating an empty IN as nothing and an empty NOT IN as everything" in seeded {
      for {
        in       <- names(ProbeFilter(score = p.in(List(1, 3))))
        inNone   <- names(ProbeFilter(score = p.in(Nil)))
        notIn    <- names(ProbeFilter(score = p.notIn(List(1, 3))))
        notInAll <- names(ProbeFilter(score = p.notIn(Nil)))
      } yield {
        in shouldBe List("alpha", "gamma")
        inNone shouldBe Nil
        notIn shouldBe List("Delta", "beta")
        notInAll shouldBe List("Delta", "alpha", "beta", "gamma")
      }
    }

    "match on ranges" in seeded {
      for {
        gt         <- names(ProbeFilter(score = p.greaterThan(2)))
        gte        <- names(ProbeFilter(score = p.greaterThanOrEqual(2)))
        lt         <- names(ProbeFilter(score = p.lessThan(2)))
        lte        <- names(ProbeFilter(score = p.lessThanOrEqual(2)))
        between    <- names(ProbeFilter(score = p.between(2, 3)))
        notBetween <- names(ProbeFilter(score = p.notBetween(2, 3)))
      } yield {
        gt shouldBe List("Delta", "gamma")
        gte shouldBe List("Delta", "beta", "gamma")
        lt shouldBe List("alpha")
        lte shouldBe List("alpha", "beta")
        between shouldBe List("beta", "gamma")
        notBetween shouldBe List("Delta", "alpha")
      }
    }

    "match on text patterns with and without case sensitivity" in seeded {
      for {
        like       <- names(ProbeFilter(name = p.like("%a")))
        ilike      <- names(ProbeFilter(name = p.like("d%", caseSensitive = false)))
        notLike    <- names(ProbeFilter(name = p.notLike("%a")))
        notIlike   <- names(ProbeFilter(name = p.notLike("d%", caseSensitive = false)))
        similar    <- names(ProbeFilter(name = p.similarTo("(alpha|beta)")))
        notSimilar <- names(ProbeFilter(name = p.notSimilarTo("(alpha|beta)")))
      } yield {
        like shouldBe List("Delta", "alpha", "beta", "gamma")
        ilike shouldBe List("Delta")
        notLike shouldBe Nil
        notIlike shouldBe List("alpha", "beta", "gamma")
        similar shouldBe List("alpha", "beta")
        notSimilar shouldBe List("Delta", "gamma")
      }
    }

    "match on nullness and booleans" in seeded {
      for {
        isNull    <- names(ProbeFilter(note = p.isNull))
        isNotNull <- names(ProbeFilter(note = p.isNotNull))
        isTrue    <- names(ProbeFilter(active = p.isTrue))
        isFalse   <- names(ProbeFilter(active = p.isFalse))
      } yield {
        isNull shouldBe List("gamma")
        isNotNull shouldBe List("Delta", "alpha", "beta")
        isTrue shouldBe List("Delta", "alpha", "gamma")
        isFalse shouldBe List("beta")
      }
    }

    "treat NULL as a value with IS DISTINCT FROM" in seeded {
      for {
        distinct    <- names(ProbeFilter(note = p.isDistinctFrom("first")))
        notDistinct <- names(ProbeFilter(note = p.isNotDistinctFrom("first")))
      } yield {
        distinct shouldBe List("Delta", "beta", "gamma")
        notDistinct shouldBe List("alpha")
      }
    }

    "combine predicates across columns with AND" in seeded {
      names(ProbeFilter(ownerId = p.equal(ownerB), score = p.greaterThan(3))).map(_ shouldBe List("Delta"))
    }
  }

  "FilteringRepository lookups" should {
    "getByFilter returns the single match and fails when there is none" in seeded {
      for {
        found   <- repo.getByFilter(ProbeFilter(name = p.equal("gamma")))
        missing <- repo.getByFilter(ProbeFilter(name = p.equal("omega"))).attempt
      } yield {
        found.id shouldBe gamma.id
        missing.isLeft shouldBe true
      }
    }

    "existsByFilter reports presence without loading rows" in seeded {
      for {
        yes <- repo.existsByFilter(ProbeFilter(ownerId = p.equal(ownerB)))
        no  <- repo.existsByFilter(ProbeFilter(score = p.greaterThan(100)))
      } yield {
        yes shouldBe true
        no shouldBe false
      }
    }

    "deleteByFilter removes only the matching rows" in seeded {
      for {
        _         <- repo.deleteByFilter(ProbeFilter(ownerId = p.equal(ownerB)))
        remaining <- repo.all.map(_.map(_.name).sorted)
      } yield remaining shouldBe List("alpha", "beta")
    }

    "round-trips array columns" in seeded {
      repo.findByIds(List(alpha.id, gamma.id)).map(_.sortBy(_.name).map(_.labels) shouldBe List(List("x", "y"), List("z")))
    }
  }

  "IdRepository" should {
    "existsById, findByIds and deleteByIds operate on the given ids and ignore empty lists" in seeded {
      for {
        exists     <- repo.existsById(alpha.id)
        notExists  <- repo.existsById(TestData.randomUuid())
        some       <- repo.findByIds(List(alpha.id, delta.id, TestData.randomUuid()))
        none       <- repo.findByIds(Nil)
        _          <- repo.deleteByIds(Nil)
        stillThere <- repo.count
        _          <- repo.deleteByIds(List(alpha.id, delta.id))
        left       <- repo.all.map(_.map(_.name).sorted)
      } yield {
        exists shouldBe true
        notExists shouldBe false
        some.map(_.name).sorted shouldBe List("Delta", "alpha")
        none shouldBe Nil
        stillThere shouldBe 4L
        left shouldBe List("beta", "gamma")
      }
    }
  }

  "ForeignIdRepository" should {
    "count, test and delete by foreign id" in seeded {
      for {
        count   <- repo.countByForeignId(ownerA)
        exists  <- repo.existsByForeignId(ownerB)
        missing <- repo.existsByForeignId(TestData.randomUuid())
        byMany  <- repo.findByForeignIds(List(ownerA, ownerB))
        byNone  <- repo.findByForeignIds(Nil)
        _       <- repo.deleteByForeignIds(Nil)
        _       <- repo.deleteByForeignIds(List(ownerA))
        left    <- repo.all.map(_.map(_.name).sorted)
      } yield {
        count shouldBe 2L
        exists shouldBe true
        missing shouldBe false
        byMany.size shouldBe 4
        byNone shouldBe Nil
        left shouldBe List("Delta", "gamma")
      }
    }
  }

  "SoftDeleteRepository" should {
    "hide soft-deleted rows from the live views but keep them reachable through the WithDeleted variants" in seeded {
      for {
        _            <- repo.softDeleteById(alpha.id, now)
        live         <- repo.findById(alpha.id)
        liveExists   <- repo.existsById(alpha.id)
        withDeleted  <- repo.findByIdWithDeleted(alpha.id)
        existsDel    <- repo.existsByIdWithDeleted(alpha.id)
        getDel       <- repo.findWithDeleted(alpha.id)
        byIdsDel     <- repo.findByIdsWithDeleted(List(alpha.id, beta.id))
        byIdsNone    <- repo.findByIdsWithDeleted(Nil)
        liveCount    <- repo.count
        totalCount   <- repo.countWithDeleted
        allIncluding <- repo.allWithDeleted
      } yield {
        live shouldBe None
        liveExists shouldBe false
        withDeleted.flatMap(_.deletedAt) shouldBe Some(now)
        existsDel shouldBe true
        getDel.id shouldBe alpha.id
        byIdsDel.map(_.name).sorted shouldBe List("alpha", "beta")
        byIdsNone shouldBe Nil
        liveCount shouldBe 3L
        totalCount shouldBe 4L
        allIncluding.size shouldBe 4
      }
    }

    "restore a soft-deleted row by id" in seeded {
      for {
        _     <- repo.softDeleteById(alpha.id, now)
        _     <- repo.restoreById(alpha.id)
        found <- repo.findById(alpha.id)
      } yield found.map(_.deletedAt) shouldBe Some(None)
    }

    "soft-delete and restore several ids at once, ignoring empty lists" in seeded {
      for {
        _        <- repo.softDeleteByIds(Nil, now)
        _        <- repo.softDeleteByIds(List(alpha.id, beta.id), now)
        afterDel <- repo.all.map(_.map(_.name).sorted)
        _        <- repo.restoreByIds(Nil)
        _        <- repo.restoreByIds(List(alpha.id))
        afterRes <- repo.all.map(_.map(_.name).sorted)
      } yield {
        afterDel shouldBe List("Delta", "gamma")
        afterRes shouldBe List("Delta", "alpha", "gamma")
      }
    }

    "soft-delete every row with softDeleteAll" in seeded {
      for {
        _     <- repo.softDeleteAll(now)
        live  <- repo.count
        total <- repo.countWithDeleted
      } yield {
        live shouldBe 0L
        total shouldBe 4L
      }
    }

    "purge only rows soft-deleted before the cutoff" in seeded {
      for {
        _     <- repo.softDeleteById(alpha.id, now.minusSeconds(3600))
        _     <- repo.softDeleteById(beta.id, now.plusSeconds(3600))
        _     <- repo.purgeDeletedBefore(now)
        total <- repo.countWithDeleted
        beta  <- repo.findByIdWithDeleted(beta.id)
        alpha <- repo.findByIdWithDeleted(alpha.id)
      } yield {
        total shouldBe 3L
        beta shouldBe defined
        alpha shouldBe None
      }
    }
  }

  "Table fragments" should {
    "render aliased and empty column lists" in {
      ProbeTable.*("t").sql shouldBe "t.id, t.owner_id, t.name, t.score, t.active, t.note, t.labels, t.deleted_at"
      ProbeTable.columnsToFragment(Nil).sql shouldBe ""
      ProbeTable.columnsToFragment(Nil, "t").sql shouldBe ""
    }
  }
}
