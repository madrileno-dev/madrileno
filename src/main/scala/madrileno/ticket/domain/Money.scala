package madrileno.ticket.domain

enum MoneyRejection {
  case CurrencyMismatch
}

// NOTE: Money accepts 0, but the event_section.price_minor and ticket.price_minor columns require > 0 (CHECK in
// V7__ticket.sql), so a zero price cannot be stored: free tickets are currently not supported. Supporting them means
// changing both the domain and those constraints together.
//
// The amount is a count of minor units (grosze, cents) held in a Long, never a floating point
// number: binary floats cannot represent most decimal fractions exactly, so sums of prices would drift.
// The constructor is private (which also makes the synthesized `apply` and `copy` private),
// so the only way to obtain a Money is `Money.from`, which enforces amount >= 0.
final case class Money private (amountMinor: Long, currency: Currency) {

  def plus(other: Money): Either[MoneyRejection, Money] = {
    if (currency != other.currency) Left(MoneyRejection.CurrencyMismatch)
    else Right(Money(amountMinor + other.amountMinor, currency))
  }
}

object Money {
  // Mirrors the `from` of kebs `Opaque` companions (Either[String, _]).
  def from(amountMinor: Long, currency: Currency): Either[String, Money] = {
    if (amountMinor >= 0) Right(Money(amountMinor, currency))
    else Left("Money amount must not be negative")
  }
}
