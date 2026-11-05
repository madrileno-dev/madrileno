package madrileno.ticket.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{TestData, TestTransactor}
import madrileno.ticket.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class TicketRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo = new TicketRepository
  private val now       = Instant.parse("2026-01-01T10:00:00Z")

  private def makeTicket(name: TicketName = TicketName("test-name")): Ticket =
    Ticket.create(TestData.randomTicketId(), name, now)

  "TicketRepository" should {
    "save and find a ticket" in withRollback {
      val entity = makeTicket()
      for {
        _       <- repo.save(entity)
        fetched <- repo.find(entity.id)
      } yield fetched shouldBe Some(entity)
    }

    "find returns None for a non-existent ticket" in withRollback {
      repo.find(TestData.randomTicketId()).map(_ shouldBe None)
    }

    "update applies the transition and persists the new aggregate" in withRollback {
      val entity = makeTicket()
      val later  = now.plusSeconds(60)
      for {
        _       <- repo.save(entity)
        result  <- repo.update(entity.id, _.rename(TicketName("updated"), later))
        fetched <- repo.find(entity.id)
      } yield {
        result.map(_.map(_.name)) shouldBe Some(Right(TicketName("updated")))
        fetched.map(_.name) shouldBe Some(TicketName("updated"))
        fetched.map(_.updatedAt) shouldBe Some(later)
      }
    }

    "update returns the rejection without persisting" in withRollback {
      val entity = makeTicket()
      for {
        _       <- repo.save(entity)
        result  <- repo.update(entity.id, _.rename(entity.name, now.plusSeconds(60)))
        fetched <- repo.find(entity.id)
      } yield {
        result shouldBe Some(Left(RenameRejection.NameUnchanged))
        fetched.map(_.updatedAt) shouldBe Some(now)
      }
    }

    "update returns None for a non-existent ticket" in withRollback {
      repo
        .update(TestData.randomTicketId(), (a: Ticket) => a.rename(TicketName("x"), now))
        .map(_ shouldBe None)
    }

    "softDelete hides the ticket from find" in withRollback {
      val entity = makeTicket()
      for {
        _           <- repo.save(entity)
        _           <- repo.softDelete(entity.id, now.plusSeconds(1))
        afterDelete <- repo.find(entity.id)
      } yield afterDelete shouldBe None
    }
  }
}
