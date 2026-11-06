package madrileno.ticket.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, SectionSpec, SeededEvent, TestApplicationLoader, TestData, TicketSeed}
import madrileno.ticket.domain.{EventSeat, EventStatus, Money, SeatLabel}
import madrileno.user.domain.User

import java.time.Instant

// Fixtures shared by the router specs of the ticket module. Split in two specs because one class with all the
// scenarios exceeds the JVM limit on the size of a constructor (64 KB), which Baklava's `path(...)` blocks fill.
trait TicketSaleFixtures { self: BaseRouteSpec & TestApplicationLoader =>

  // Someone who owns none of the seeded tickets.
  protected val stranger: AuthContext = AuthContext(TestData.user())

  // An event with seats A-1..A-3 and a customer who exists in the database (orders reference their customer).
  protected final case class SeededSale(event: SeededEvent, customer: User) {
    def auth: AuthContext              = AuthContext(customer)
    def seat(label: String): EventSeat = event.seat(label)
  }

  protected val seatPrice: Money = TestData.ticketPrice(12_000L)

  protected def seedSale(status: EventStatus = EventStatus.OnSale, startsAt: Instant = Instant.now().plusSeconds(86400)): SeededSale = {
    val customer = TestData.user()
    val event    = application.transactor
      .inSession {
        for {
          _     <- application.userRepository.create(customer, Instant.now())
          event <- TicketSeed.event(List(SectionSpec("Parter", seatPrice, List("A-1", "A-2", "A-3").map(SeatLabel(_)))), status, startsAt)
        } yield event
      }
      .unsafeRunSync()
    SeededSale(event, customer)
  }
}
