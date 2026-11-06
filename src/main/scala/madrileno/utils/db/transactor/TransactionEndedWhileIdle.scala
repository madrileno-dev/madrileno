package madrileno.utils.db.transactor

import scala.concurrent.duration.FiniteDuration

final class TransactionEndedWhileIdle(timeout: FiniteDuration, cause: Throwable)
    extends Exception(
      s"The connection was lost in a transaction open longer than $timeout, so the database most likely ended it for sitting idle (pg.idle-in-transaction-timeout). Keep slow work, such as calls to other services, outside inTransaction",
      cause
    )
