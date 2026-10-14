package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait ForeignIdTable[Id] {
  def foreignId: Column[Id]
}

trait ForeignIdRepository[A, Id] extends BaseRepository[A] {
  def findByForeignId(foreignId: Id, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[List[A]] = {
    session
      .execute(
        sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.foreignId.n} = ${table.foreignId.c} ${lock.fragment}".query(table.c)
      )(foreignId)
  }

  def findByForeignIds(foreignIds: List[Id], lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[List[A]] =
    if (foreignIds.isEmpty) IO.pure(Nil)
    else
      session.execute(
        sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.foreignId.n} IN (${table.foreignId.c.list(foreignIds)}) ${lock.fragment}"
          .query(table.c)
      )(foreignIds)

  def countByForeignId(foreignId: Id)(using session: Session[IO]): IO[Long] = {
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE $baseFilter AND ${table.foreignId.n} = ${table.foreignId.c}".query(int8))(foreignId)
  }

  def existsByForeignId(foreignId: Id)(using session: Session[IO]): IO[Boolean] =
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE $baseFilter AND ${table.foreignId.n} = ${table.foreignId.c} LIMIT 1".query(int4))(foreignId)
      .map(_.isDefined)

  def deleteByForeignId(foreignId: Id)(using session: Session[IO]): IO[Unit] =
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.foreignId.n} = ${table.foreignId.c}".command)(foreignId)
      .void

  def deleteByForeignIds(foreignIds: List[Id])(using session: Session[IO]): IO[Unit] =
    if (foreignIds.isEmpty) IO.unit
    else
      session
        .execute(sql"DELETE FROM ${table.n} WHERE ${table.foreignId.n} IN (${table.foreignId.c.list(foreignIds)})".command)(foreignIds)
        .void

  override val table: Table[A] & ForeignIdTable[Id]
}
