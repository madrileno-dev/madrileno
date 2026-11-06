package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import fs2.Stream
import skunk.*
import skunk.data.{Identifier, Notification}
import skunk.util.Origin

import scala.concurrent.duration.Duration

type DB[A]              = Session[IO] ?=> IO[A]
type DBInTransaction[A] = Session[IO] ?=> Transaction[IO] ?=> IO[A]

trait Transactor {

  /** Runs `f` within a single database transaction. All operations share the same session and transaction. */
  def inTransaction[A](f: DBInTransaction[A]): IO[A]

  def savepoint(using
    transaction: Transaction[IO],
    origin: Origin
  ): IO[transaction.Savepoint] = {
    transaction.savepoint
  }

  /** Runs `f` with a session from the pool. Each `inSession` call gets its own session — multiple `inSession` calls are NOT transactional together.
    * Use `inTransaction` for atomicity.
    */
  def inSession[A](f: DB[A]): IO[A]

  /** Runs `f` on a connection of its own, opened for it and closed after, so it neither waits for nor takes a pooled one. Meant for health checks.
    */
  def inNewConnection[A](f: DB[A]): IO[A] = inSession(f)

  def notify(channel: Identifier, payload: String): IO[Unit]

  def listen(channel: Identifier, maxQueued: Int): Resource[IO, Stream[IO, Notification[String]]]

  /** The same pool, with `inSession`, `inTransaction` and `notify` bounded by `timeout` instead of `pg.operation-timeout`. `Duration.Inf` lifts the
    * bound for a long batch.
    */
  def withOperationTimeout(timeout: Duration): Transactor = this
}
