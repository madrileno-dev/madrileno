package madrileno.ticket.domain

// Case names are persisted as text, do not rename them.
enum TicketStatus {
  case Held, Paid, Cancelled
}
