package madrileno.user.repositories

import cats.effect.IO
import madrileno.user.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*

import java.net.URI
import java.time.Instant

private[repositories] final case class UserRow(
  id: UserId,
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[URI],
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant],
  blockedAt: Option[Instant]) {
  def toUser: User = {
    import io.scalaland.chimney.dsl.*
    this.into[User].transform
  }

  def update(user: User, now: Instant): UserRow = {
    import io.scalaland.chimney.dsl.*
    this.patchUsing(user).copy(updatedAt = now)
  }
}

private[repositories] object UserRow {
  def apply(user: User, now: Instant): UserRow = {
    import io.scalaland.chimney.dsl.*
    user
      .into[UserRow]
      .withFieldConst(_.createdAt, now)
      .withFieldConst(_.updatedAt, now)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

private[repositories] object UserRowTable extends Table[UserRow]("\"user\"") with IdTable[UserRow, UserId] with SoftDeleteTable {
  override val id: Column[UserId]                 = column("id", uuid.as[UserId])
  val fullName: Column[Option[FullName]]          = column("full_name", text.as[FullName].opt)
  val emailAddress: Column[Option[EmailAddress]]  = column("email", text.as[EmailAddress].opt)
  val emailVerified: Column[Boolean]              = column("email_verified", bool)
  val avatarUrl: Column[Option[URI]]              = column("avatar_url", text.asConvertedTo[URI].opt)
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  val blockedAt: Column[Option[Instant]]          = column("blocked_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[UserRow]) =
    (id, fullName, emailAddress, emailVerified, avatarUrl, createdAt, updatedAt, deletedAt, blockedAt)
}

private[repositories] final case class UserRowFilter(
  id: SqlPredicate[UserId] = p.any,
  emailAddress: SqlPredicate[Option[EmailAddress]] = p.any,
  emailVerified: SqlPredicate[Boolean] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (UserRowTable.id, UserRowTable.emailAddress, UserRowTable.emailVerified, UserRowTable.deletedAt))
}

class UserRepository {
  def create(user: User, now: Instant): DB[User] = {
    val row = UserRow(user, now)
    repository.create(row).as(row.toUser)
  }

  def find(id: UserId): DB[Option[User]] =
    repository.findById(id).map(_.map(_.toUser))

  def get(id: UserId): DB[User] =
    repository.getById(id).map(_.toUser)

  def update(
    id: UserId,
    f: User => User,
    now: Instant
  ): DB[Boolean] =
    repository.updateById(id, userRow => userRow.update(f(userRow.toUser), now))

  def softDelete(id: UserId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  def findIncludingDeleted(id: UserId): DB[Option[User]] =
    repository.findByIdWithDeleted(id).map(_.map(_.toUser))

  def anonymize(id: UserId, now: Instant): DBInTransaction[Boolean] =
    repository.findById(id, Lock.ForUpdate).flatMap {
      case None      => IO.pure(false)
      case Some(row) =>
        repository
          .update(row.copy(fullName = None, emailAddress = None, emailVerified = false, avatarUrl = None, deletedAt = Some(now), updatedAt = now))
          .as(true)
    }

  private val repository: IdRepository[UserRow, UserId] & SoftDeleteRepository[UserRow, UserId] & FilteringRepository[UserRow, UserRowFilter] =
    new IdRepository[UserRow, UserId](_.id) with SoftDeleteRepository[UserRow, UserId] with FilteringRepository[UserRow, UserRowFilter] {
      override val table: UserRowTable.type = UserRowTable
    }
}
