package __package__.__aggregate__.repositories

import cats.effect.IO
import __package__.__aggregate__.domain.*
import __package__.utils.db.dsl.*
import __package__.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class __Aggregate__Row(
  id: __Aggregate__Id,
  name: __Aggregate__Name,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def to__Aggregate__: __Aggregate__ = {
    import io.scalaland.chimney.dsl.*
    this.into[__Aggregate__].transform
  }
}

private[repositories] object __Aggregate__Row {
  def apply(__aggregate__: __Aggregate__): __Aggregate__Row = {
    import io.scalaland.chimney.dsl.*
    __aggregate__.into[__Aggregate__Row].transform
  }
}

private[repositories] object __Aggregate__RowTable
    extends Table[__Aggregate__Row]("__aggregate__")
    with IdTable[__Aggregate__Row, __Aggregate__Id]
    with SoftDeleteTable {
  override val id: Column[__Aggregate__Id]        = column("id", uuid.as[__Aggregate__Id])
  val name: Column[__Aggregate__Name]             = column("name", text.as[__Aggregate__Name])
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[__Aggregate__Row]) =
    (id, name, createdAt, updatedAt, deletedAt)
}

private[repositories] final case class __Aggregate__RowFilter(id: SqlPredicate[__Aggregate__Id] = p.any, deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (__Aggregate__RowTable.id, __Aggregate__RowTable.deletedAt))
}

class __Aggregate__Repository {
  def save(__aggregate__: __Aggregate__): DB[Unit] =
    repository.create(__Aggregate__Row(__aggregate__)).void

  def find(id: __Aggregate__Id): DB[Option[__Aggregate__]] =
    repository.findById(id).map(_.map(_.to__Aggregate__))

  def update[E](id: __Aggregate__Id, f: __Aggregate__ => Either[E, __Aggregate__]): DBInTransaction[Option[Either[E, __Aggregate__]]] =
    repository.findById(id, Lock.ForUpdate).map(_.map(_.to__Aggregate__)).flatMap {
      case None        => IO.pure(None)
      case Some(value) =>
        f(value) match {
          case Left(e)        => IO.pure(Some(Left(e)))
          case Right(updated) => repository.update(__Aggregate__Row(updated)).as(Some(Right(updated)))
        }
    }

  def softDelete(id: __Aggregate__Id, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  private val repository: IdRepository[__Aggregate__Row, __Aggregate__Id] & SoftDeleteRepository[
    __Aggregate__Row,
    __Aggregate__Id
  ] & FilteringRepository[__Aggregate__Row, __Aggregate__RowFilter] =
    new IdRepository[__Aggregate__Row, __Aggregate__Id](_.id)
      with SoftDeleteRepository[__Aggregate__Row, __Aggregate__Id]
      with FilteringRepository[__Aggregate__Row, __Aggregate__RowFilter] {
      override val table: __Aggregate__RowTable.type = __Aggregate__RowTable
    }
}
