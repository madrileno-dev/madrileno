package madrileno.ticket.domain

// Shared validation of the free-text value objects (EventName, SectionName, SeatLabel, ChargeId).
// The limits protect the database, not the business: Postgres text cannot hold a NUL character (an error, i.e. a 500)
// and unbounded values would bloat rows and indexes. The same length limit is repeated as a CHECK in V7__ticket.sql.
private[domain] object TextRules {
  val MaxLength: Int = 200

  // Trims first, then checks. Note that String.trim also strips control characters at the edges (everything <= U+0020),
  // so only control characters INSIDE the text are rejected. Length is counted in code points, like Postgres char_length.
  def validate(value: String, what: String): Either[String, String] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) Left(s"$what must not be empty")
    else if (trimmed.codePointCount(0, trimmed.length) > MaxLength) Left(s"$what must be at most $MaxLength characters long")
    else if (trimmed.codePoints().anyMatch(cp => Character.isISOControl(cp))) Left(s"$what must not contain control characters")
    else Right(trimmed)
  }
}
