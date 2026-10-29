package madrileno.auction.repositories

import cats.effect.IO
import cats.syntax.all.*
import madrileno.auction.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.storage.StorageKey
import org.http4s.Header
import org.http4s.headers.`Content-Type`
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

private[repositories] final case class AuctionImageRow(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  fileName: String,
  contentType: `Content-Type`,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant],
  width: Option[Width],
  height: Option[Height],
  format: Option[ImageFormat],
  analyzedAt: Option[Instant]) {
  def toAuctionImage: AuctionImage = {
    import io.scalaland.chimney.dsl.*
    this.into[AuctionImage].transform
  }
}

private[repositories] object AuctionImageRow {
  def apply(image: AuctionImage): AuctionImageRow = {
    import io.scalaland.chimney.dsl.*
    image.into[AuctionImageRow].transform
  }
}

private[repositories] object AuctionImageRowTable
    extends Table[AuctionImageRow]("auction_image")
    with IdTable[AuctionImageRow, AuctionImageId]
    with SoftDeleteTable
    with ForeignIdTable[AuctionId] {
  private val contentTypeCodec: Codec[`Content-Type`] = text.imap { s =>
    `Content-Type`.parse(s).getOrElse(throw new IllegalStateException(s"Invalid content-type in DB: $s"))
  }(Header[`Content-Type`].value)

  override val id: Column[AuctionImageId]         = column("id", uuid.as[AuctionImageId])
  val auctionId: Column[AuctionId]                = column("auction_id", uuid.as[AuctionId])
  val storageKey: Column[StorageKey]              = column("storage_key", text.as[StorageKey])
  val fileName: Column[String]                    = column("file_name", text)
  val contentType: Column[`Content-Type`]         = column("content_type", contentTypeCodec)
  val sizeBytes: Column[SizeBytes]                = column("size_bytes", int8.as[SizeBytes])
  val position: Column[ImagePosition]             = column("position", int4.as[ImagePosition])
  val uploadedAt: Column[Instant]                 = column("uploaded_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  val width: Column[Option[Width]]                = column("width", int4.as[Width].opt)
  val height: Column[Option[Height]]              = column("height", int4.as[Height].opt)
  val format: Column[Option[ImageFormat]]         = column("format", text.asEnum[ImageFormat].opt)
  val analyzedAt: Column[Option[Instant]]         = column("analyzed_at", timestamptz.asInstant.opt)

  override val foreignId: Column[AuctionId] = auctionId

  def mapping: (List[Column[?]], Codec[AuctionImageRow]) =
    (id, auctionId, storageKey, fileName, contentType, sizeBytes, position, uploadedAt, deletedAt, width, height, format, analyzedAt)
}

private[repositories] final case class AuctionImageVariantRow(
  id: AuctionImageVariantId,
  auctionImageId: AuctionImageId,
  spec: VariantSpec,
  storageKey: StorageKey,
  width: Width,
  height: Height,
  format: ImageFormat,
  generatedAt: Instant) {
  def toAuctionImageVariant: AuctionImageVariant = {
    import io.scalaland.chimney.dsl.*
    this.into[AuctionImageVariant].transform
  }
}

private[repositories] object AuctionImageVariantRow {
  def apply(variant: AuctionImageVariant): AuctionImageVariantRow = {
    import io.scalaland.chimney.dsl.*
    variant.into[AuctionImageVariantRow].transform
  }
}

private[repositories] object AuctionImageVariantRowTable
    extends Table[AuctionImageVariantRow]("auction_image_variant")
    with IdTable[AuctionImageVariantRow, AuctionImageVariantId]
    with ForeignIdTable[AuctionImageId] {
  override val id: Column[AuctionImageVariantId] = column("id", uuid.as[AuctionImageVariantId])
  val auctionImageId: Column[AuctionImageId]     = column("auction_image_id", uuid.as[AuctionImageId])
  val spec: Column[VariantSpec]                  = column("spec", text.asEnum[VariantSpec])
  val storageKey: Column[StorageKey]             = column("storage_key", text.as[StorageKey])
  val width: Column[Width]                       = column("width", int4.as[Width])
  val height: Column[Height]                     = column("height", int4.as[Height])
  val format: Column[ImageFormat]                = column("format", text.asEnum[ImageFormat])
  val generatedAt: Column[Instant]               = column("generated_at", timestamptz.asInstant)

  override val foreignId: Column[AuctionImageId] = auctionImageId

  def mapping: (List[Column[?]], Codec[AuctionImageVariantRow]) =
    (id, auctionImageId, spec, storageKey, width, height, format, generatedAt)
}

private[repositories] final case class AuctionImageVariantRowFilter(
  auctionImageId: SqlPredicate[AuctionImageId] = p.any,
  spec: SqlPredicate[VariantSpec] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (AuctionImageVariantRowTable.auctionImageId, AuctionImageVariantRowTable.spec))
}

private[repositories] final case class AuctionImageRowFilter(
  id: SqlPredicate[AuctionImageId] = p.any,
  auctionId: SqlPredicate[AuctionId] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (AuctionImageRowTable.id, AuctionImageRowTable.auctionId, AuctionImageRowTable.deletedAt))

  override def orderByFragment: Fragment[Void] = orderByColumns(AuctionImageRowTable.position -> true)
}

class AuctionImageRepository {
  def save(image: AuctionImage): DB[Unit] =
    repository.create(AuctionImageRow(image)).void

  def find(id: AuctionImageId): DB[Option[AuctionImage]] =
    repository.findById(id).map(_.map(_.toAuctionImage))

  def findIncludingDeleted(id: AuctionImageId): DB[Option[AuctionImage]] =
    repository.findByIdWithDeleted(id).map(_.map(_.toAuctionImage))

  def listByAuction(auctionId: AuctionId): DB[List[AuctionImage]] =
    repository.findByFilter(AuctionImageRowFilter(auctionId = p.equal(auctionId))).map(_.map(_.toAuctionImage))

  def listByAuctionForUpdate(auctionId: AuctionId): DBInTransaction[List[AuctionImage]] =
    repository.findByFilter(AuctionImageRowFilter(auctionId = p.equal(auctionId)), Lock.ForUpdate).map(_.map(_.toAuctionImage))

  def nextPosition(auctionId: AuctionId): DB[Int] = {
    val table = AuctionImageRowTable
    val query = sql"""SELECT COALESCE(MAX(${table.position.n}) + 1, 0)
                      FROM ${table.n}
                      WHERE ${table.auctionId.n} = ${table.auctionId.c}
                        AND ${table.deletedAt.n} IS NULL""".query(int4)
    summon[Session[IO]].unique(query)(auctionId)
  }

  def softDelete(id: AuctionImageId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  def markAnalyzed(
    id: AuctionImageId,
    sizeBytes: SizeBytes,
    width: Width,
    height: Height,
    format: ImageFormat,
    now: Instant
  ): DB[Unit] =
    repository
      .updateById(id, _.copy(sizeBytes = sizeBytes, width = Some(width), height = Some(height), format = Some(format), analyzedAt = Some(now)))
      .void

  def saveVariant(variant: AuctionImageVariant): DB[Unit] = {
    val table   = AuctionImageVariantRowTable
    val command = sql"""INSERT INTO ${table.n} (${table.*}) VALUES (${table.c})
                        ON CONFLICT (${table.auctionImageId.n}, ${table.spec.n}) DO NOTHING""".command
    summon[Session[IO]].execute(command)(AuctionImageVariantRow(variant)).void
  }

  def listVariants(imageId: AuctionImageId): DB[List[AuctionImageVariant]] =
    variantRepository.findByForeignId(imageId).map(_.map(_.toAuctionImageVariant))

  def listVariantsByImages(imageIds: List[AuctionImageId]): DB[Map[AuctionImageId, List[AuctionImageVariant]]] =
    variantRepository.findByForeignIds(imageIds).map(_.map(_.toAuctionImageVariant).groupBy(_.auctionImageId))

  def findVariant(imageId: AuctionImageId, spec: VariantSpec): DB[Option[AuctionImageVariant]] =
    variantRepository
      .findOneByFilter(AuctionImageVariantRowFilter(auctionImageId = p.equal(imageId), spec = p.equal(spec)))
      .map(_.map(_.toAuctionImageVariant))

  def bulkSetPositions(auctionId: AuctionId, updates: List[(AuctionImageId, ImagePosition)]): DBInTransaction[Unit] = {
    val table   = AuctionImageRowTable
    val command = sql"""UPDATE ${table.n} SET ${table.position.n} = $int4
                        WHERE ${table.id.n} = ${table.id.c}
                          AND ${table.auctionId.n} = ${table.auctionId.c}
                          AND ${table.deletedAt.n} IS NULL""".command
    val session = summon[Session[IO]]
    val phase1  = updates.zipWithIndex.traverse_ { case ((id, _), idx) => session.execute(command)((-(idx + 1), id, auctionId)).void }
    val phase2  = updates.traverse_ { case (id, pos) => session.execute(command)((pos.unwrap, id, auctionId)).void }
    phase1 >> phase2
  }

  private val repository: IdRepository[AuctionImageRow, AuctionImageId] & SoftDeleteRepository[AuctionImageRow, AuctionImageId] & ForeignIdRepository[
    AuctionImageRow,
    AuctionId
  ] & FilteringRepository[AuctionImageRow, AuctionImageRowFilter] =
    new IdRepository[AuctionImageRow, AuctionImageId](_.id)
      with SoftDeleteRepository[AuctionImageRow, AuctionImageId]
      with ForeignIdRepository[AuctionImageRow, AuctionId]
      with FilteringRepository[AuctionImageRow, AuctionImageRowFilter] {
      override val table: AuctionImageRowTable.type = AuctionImageRowTable
    }

  private val variantRepository: IdRepository[AuctionImageVariantRow, AuctionImageVariantId] & ForeignIdRepository[
    AuctionImageVariantRow,
    AuctionImageId
  ] & FilteringRepository[AuctionImageVariantRow, AuctionImageVariantRowFilter] =
    new IdRepository[AuctionImageVariantRow, AuctionImageVariantId](_.id)
      with ForeignIdRepository[AuctionImageVariantRow, AuctionImageId]
      with FilteringRepository[AuctionImageVariantRow, AuctionImageVariantRowFilter] {
      override val table: AuctionImageVariantRowTable.type = AuctionImageVariantRowTable
    }
}
