package madrileno.ticket.domain

import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class TicketSpec extends AnyWordSpec with Matchers {

  private val now = Instant.parse("2026-01-01T10:00:00Z")
  private val id  = TestData.randomTicketId()

  "TicketName" should {
    "accept non-empty strings" in {
      TicketName("hello").toString shouldBe "hello"
    }

    "trim whitespace" in {
      TicketName("  hello  ").toString shouldBe "hello"
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException](TicketName(""))
    }

    "reject whitespace-only strings" in {
      assertThrows[IllegalArgumentException](TicketName("   "))
    }
  }

  "Ticket.create" should {
    "set createdAt = updatedAt = now and leave deletedAt empty" in {
      val entity = Ticket.create(id, TicketName("original"), now)
      entity.createdAt shouldBe now
      entity.updatedAt shouldBe now
      entity.deletedAt shouldBe None
    }
  }

  "Ticket.rename" should {
    val entity = Ticket.create(id, TicketName("original"), now)

    "update name and bump updatedAt" in {
      val later = now.plusSeconds(60)
      entity.rename(TicketName("updated"), later) shouldBe
        Right(entity.copy(name = TicketName("updated"), updatedAt = later))
    }

    "reject when the name is unchanged" in {
      entity.rename(entity.name, now.plusSeconds(60)) shouldBe Left(RenameRejection.NameUnchanged)
    }
  }
}
