package madrileno.auth.repositories

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

private[repositories] final case class RefreshTokenRow(
  id: RefreshTokenId,
  familyId: RefreshTokenFamilyId,
  familyCreatedAt: Instant,
  secretHash: RefreshTokenSecretHash,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress,
  createdAt: Instant,
  usedAt: Option[Instant],
  successor: Option[SealedRefreshTokenSecret],
  deletedAt: Option[Instant],
  expiresAt: Instant) {
  def toRefreshToken: RefreshToken = {
    import io.scalaland.chimney.dsl.*
    this.into[RefreshToken].transform
  }
}

private[repositories] object RefreshTokenRow {
  def apply(refreshToken: RefreshToken): RefreshTokenRow = {
    import io.scalaland.chimney.dsl.*
    refreshToken
      .into[RefreshTokenRow]
      .transform
  }
}

private[repositories] object RefreshTokenRowTable
    extends Table[RefreshTokenRow]("refresh_token")
    with IdTable[RefreshTokenRow, RefreshTokenId]
    with SoftDeleteTable
    with ForeignIdTable[UserId] {
  override val id: Column[RefreshTokenId]        = column("id", uuid.as[RefreshTokenId])
  val familyId: Column[RefreshTokenFamilyId]     = column("family_id", uuid.as[RefreshTokenFamilyId])
  val familyCreatedAt: Column[Instant]           = column("family_created_at", timestamptz.asInstant)
  val secretHash: Column[RefreshTokenSecretHash] = column("secret_hash", text.as[RefreshTokenSecretHash].redacted)
  val userId: Column[UserId]                     = column("user_id", uuid.as[UserId])
  val userAgent: Column[UserAgent]               = column("user_agent", text.as[UserAgent])
  val ipAddress: Column[IpAddress]               = column(
    "ip_address",
    text.imap(IpAddress.fromString.andThen(_.getOrElse(throw new IllegalStateException("Invalid IP address format"))))(_.toString)
  )
  val createdAt: Column[Instant]                          = column("created_at", timestamptz.asInstant)
  val usedAt: Column[Option[Instant]]                     = column("used_at", timestamptz.asInstant.opt)
  val successor: Column[Option[SealedRefreshTokenSecret]] = column("successor_secret", text.as[SealedRefreshTokenSecret].redacted.opt)
  override val deletedAt: Column[Option[Instant]]         = column("deleted_at", timestamptz.asInstant.opt)
  val expiresAt: Column[Instant]                          = column("expires_at", timestamptz.asInstant)

  override val foreignId: Column[UserId] = userId

  override def mapping: (List[Column[?]], Codec[RefreshTokenRow]) =
    (id, familyId, familyCreatedAt, secretHash, userId, userAgent, ipAddress, createdAt, usedAt, successor, deletedAt, expiresAt)
}

private[repositories] final case class RefreshTokenRowFilter(
  id: SqlPredicate[RefreshTokenId] = p.any,
  familyId: SqlPredicate[RefreshTokenFamilyId] = p.any,
  secretHash: SqlPredicate[RefreshTokenSecretHash] = p.any,
  userId: SqlPredicate[UserId] = p.any,
  userAgent: SqlPredicate[UserAgent] = p.any,
  usedAt: SqlPredicate[Instant] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any,
  expiresAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {

  override def filterFragment: AppliedFragment = SqlFilterDerivation.filterFragment(
    this,
    (
      RefreshTokenRowTable.id,
      RefreshTokenRowTable.familyId,
      RefreshTokenRowTable.secretHash,
      RefreshTokenRowTable.userId,
      RefreshTokenRowTable.userAgent,
      RefreshTokenRowTable.usedAt,
      RefreshTokenRowTable.deletedAt,
      RefreshTokenRowTable.expiresAt
    )
  )
}

private[repositories] object RefreshTokenRowFilter {
  def live(now: Instant): RefreshTokenRowFilter =
    RefreshTokenRowFilter(usedAt = p.isNull, deletedAt = p.isNull, expiresAt = p.greaterThan(now))
}

class RefreshTokenRepository {
  def save(refreshToken: RefreshToken): DB[RefreshToken] = {
    repository.create(RefreshTokenRow(refreshToken)).map(_.toRefreshToken)
  }

  def listActive(userId: UserId, now: Instant): DB[List[RefreshToken]] = {
    repository.findByFilter(RefreshTokenRowFilter.live(now).copy(userId = p.equal(userId))).map(_.map(_.toRefreshToken))
  }

  def listActiveByUserAgent(
    userId: UserId,
    userAgent: UserAgent,
    now: Instant
  ): DB[List[RefreshToken]] = {
    repository
      .findByFilter(RefreshTokenRowFilter.live(now).copy(userId = p.equal(userId), userAgent = p.equal(userAgent)))
      .map(_.map(_.toRefreshToken))
  }

  def listActiveByFamily(familyId: RefreshTokenFamilyId, now: Instant): DB[List[RefreshToken]] = {
    repository.findByFilter(RefreshTokenRowFilter.live(now).copy(familyId = p.equal(familyId))).map(_.map(_.toRefreshToken))
  }

  def find(id: RefreshTokenId): DB[Option[RefreshToken]] = {
    repository.findOneByFilter(RefreshTokenRowFilter(id = p.equal(id))).map(_.map(_.toRefreshToken))
  }

  def findBySecretHash(secretHash: RefreshTokenSecretHash): DB[Option[RefreshToken]] = {
    repository.findOneByFilter(RefreshTokenRowFilter(secretHash = p.equal(secretHash))).map(_.map(_.toRefreshToken))
  }

  def findAndLockFamilyBySecretHash(secretHash: RefreshTokenSecretHash): DBInTransaction[Option[RefreshToken]] = {
    findBySecretHash(secretHash).flatMap {
      case None        => IO.pure(None)
      case Some(token) => lockFamily(token.familyId) *> findForUpdateBySecretHash(secretHash)
    }
  }

  private def findForUpdateBySecretHash(secretHash: RefreshTokenSecretHash): DBInTransaction[Option[RefreshToken]] = {
    repository.findOneByFilter(RefreshTokenRowFilter(secretHash = p.equal(secretHash)), Lock.ForUpdate).map(_.map(_.toRefreshToken))
  }

  def lockFamily(familyId: RefreshTokenFamilyId): DBInTransaction[Unit] = {
    lockFamilyKey(familyLockKey(familyId))
  }

  def lockFamilies(familyIds: List[RefreshTokenFamilyId]): DBInTransaction[Unit] = {
    familyIds.map(familyLockKey).distinct.sorted.traverse_(lockFamilyKey)
  }

  private def lockFamilyKey(key: Int): DBInTransaction[Unit] = {
    AdvisoryLock.transactionScoped(RefreshTokenRepository.FamilyLockName, key)
  }

  private def familyLockKey(familyId: RefreshTokenFamilyId): Int = familyId.unwrap.hashCode

  def update(refreshToken: RefreshToken): DB[Unit] = {
    repository.update(RefreshTokenRow(refreshToken))
  }

  def revokeAllForUser(userId: UserId, now: Instant): DB[Unit] =
    repository.softDeleteByFilter(RefreshTokenRowFilter(userId = p.equal(userId), deletedAt = p.isNull), now)

  def revokeFamily(familyId: RefreshTokenFamilyId, now: Instant): DB[Unit] =
    repository.softDeleteByFilter(RefreshTokenRowFilter(familyId = p.equal(familyId), deletedAt = p.isNull), now)

  def deleteStaleBefore(cutoff: Instant): DB[Unit] = {
    val session = summon[Session[IO]]
    val table   = RefreshTokenRowTable
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.expiresAt.n} < ${table.expiresAt.c}".command)(cutoff)
      .void
  }

  private val repository: IdRepository[RefreshTokenRow, RefreshTokenId] & SoftDeleteRepository[RefreshTokenRow, RefreshTokenId] & ForeignIdRepository[
    RefreshTokenRow,
    UserId
  ] & FilteringRepository[RefreshTokenRow, RefreshTokenRowFilter] =
    new IdRepository[RefreshTokenRow, RefreshTokenId](_.id)
      with SoftDeleteRepository[RefreshTokenRow, RefreshTokenId]
      with ForeignIdRepository[RefreshTokenRow, UserId]
      with FilteringRepository[RefreshTokenRow, RefreshTokenRowFilter] {

      override val table: RefreshTokenRowTable.type = RefreshTokenRowTable
    }
}

object RefreshTokenRepository {
  private val FamilyLockName: String = "refresh-token-family"
}
