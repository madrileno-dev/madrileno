package madrileno.ticket.domain

import madrileno.user.domain.UserId

import java.time.Instant

// Groups the tickets a customer held in one request. Produced by `Event.holdSeats`, never mutated afterwards.
final case class Order(
  id: OrderId,
  eventId: EventId,
  customerId: UserId,
  createdAt: Instant)
