package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

enum Lock(val fragment: Fragment[Void]) {
  case NoLock         extends Lock(sql"")
  case ForUpdate      extends Lock(sql"FOR UPDATE")
  case ForNoKeyUpdate extends Lock(sql"FOR NO KEY UPDATE")
  case ForShare       extends Lock(sql"FOR SHARE")
  case ForKeyShare    extends Lock(sql"FOR KEY SHARE")
}

trait IdTable[A, Id] {
  def id: Column[Id]
}

trait IdRepository[A, Id](val getId: A => Id) extends BaseRepository[A] {
  def create(a: A)(using session: Session[IO]): IO[A] =
    session.unique(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) RETURNING ${table.*}".query(table.c))(a)

  def createAll(s: List[A])(using session: Session[IO]): IO[List[A]] =
    if (s.isEmpty) IO.pure(Nil)
    else {
      val enc = table.c.values.list(s)
      session.execute(sql"INSERT INTO ${table.n} (${table.*}) VALUES $enc RETURNING ${table.*}".query(table.c))(s)
    }

  def upsert(a: A)(using session: Session[IO]): IO[Unit] =
    session
      .execute(
        sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) ON CONFLICT (${table.id.n}) DO UPDATE SET (${table.*}) = (${table.c})".command
      )((a, a))
      .void

  def findById(id: Id, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[Option[A]] =
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c} ${lock.fragment}".query(table.c))(id)

  def existsById(id: Id)(using session: Session[IO]): IO[Boolean] =
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".query(int4))(id)
      .map(_.isDefined)

  def findByIds(ids: List[Id], lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[List[A]] =
    if (ids.isEmpty) IO.pure(Nil)
    else
      session.execute(
        sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} IN (${table.id.c.list(ids)}) ${lock.fragment}".query(table.c)
      )(ids)

  def getById(id: Id, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[A] =
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c} ${lock.fragment}".query(table.c))(id)

  def all(using session: Session[IO]): IO[List[A]] =
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter".query(table.c))

  def count(using session: Session[IO]): IO[Long] =
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE $baseFilter".query(int8))

  def update(toBeUpdated: A)(using session: Session[IO]): IO[Unit] =
    session
      .execute(sql"UPDATE ${table.n} SET (${table.*}) = (${table.c}) WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".command)(
        (toBeUpdated, getId(toBeUpdated))
      )
      .void

  def updateById(id: Id, transform: A => A)(using session: Session[IO]): IO[Boolean] =
    session
      .option(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c} FOR UPDATE".query(table.c))(id)
      .flatMap {
        case Some(obj) =>
          val toBeUpdated = transform(obj)
          session
            .execute(sql"UPDATE ${table.n} SET (${table.*}) = (${table.c}) WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".command)(
              (toBeUpdated, getId(toBeUpdated))
            )
            .as(true)
        case None => IO.pure(false)
      }

  def deleteById(id: Id)(using session: Session[IO]): IO[Unit] =
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.id.n} = ${table.id.c}".command)(id)
      .void

  def deleteByIds(ids: List[Id])(using session: Session[IO]): IO[Unit] =
    if (ids.isEmpty) IO.unit
    else
      session
        .execute(sql"DELETE FROM ${table.n} WHERE ${table.id.n} IN (${table.id.c.list(ids)})".command)(ids)
        .void

  def delete(a: A)(using session: Session[IO]): IO[Unit] = deleteById(getId(a))

  def deleteAll(using session: Session[IO]): IO[Unit] =
    session.execute(sql"DELETE FROM ${table.n}".command).void

  override val table: IdTable[A, Id] & Table[A]
}
