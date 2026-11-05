package madrileno.utils.db.transactor

import scala.concurrent.duration.FiniteDuration

final class TransactionEndedWhileIdle(timeout: FiniteDuration, cause: Throwable)
    extends Exception(
      s"The database ended this transaction after it sat idle longer than $timeout (pg.idle-in-transaction-timeout). Keep slow work, such as calls to other services, outside inTransaction",
      cause
    )
