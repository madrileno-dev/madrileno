package madrileno.ticket.domain

import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

opaque type EventId = UUID
object EventId extends Opaque[EventId, UUID]

opaque type SeatId = UUID
object SeatId extends Opaque[SeatId, UUID]

opaque type OrderId = UUID
object OrderId extends Opaque[OrderId, UUID]
