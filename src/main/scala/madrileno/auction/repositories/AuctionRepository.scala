package madrileno.auction.repositories

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.pagination.PageRequest
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.Currency

private[repositories] final case class AuctionRow(
  id: AuctionId,
  sellerId: UserId,
  wineName: WineName,
  vintage: Option[Vintage],
  color: WineColor,
  region: Region,
  appellation: Appellation,
  producerName: ProducerName,
  bottleSize: BottleSize,
  bottleCount: BottleCount,
  description: Option[Description],
  startingPrice: Price,
  currency: Currency,
  status: AuctionStatus,
  startsAt: Instant,
  endsAt: Instant,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def toAuction: Auction = {
    import io.scalaland.chimney.dsl.*
    this.into[Auction].transform
  }
}

private[repositories] object AuctionRow {
  def apply(auction: Auction): AuctionRow = {
    import io.scalaland.chimney.dsl.*
    auction
      .into[AuctionRow]
      .transform
  }
}

private[repositories] object AuctionRowTable
    extends Table[AuctionRow]("auction")
    with IdTable[AuctionRow, AuctionId]
    with SoftDeleteTable
    with ForeignIdTable[UserId] {
  override val id: Column[AuctionId]              = column("id", uuid.as[AuctionId])
  val sellerId: Column[UserId]                    = column("seller_id", uuid.as[UserId])
  val wineName: Column[WineName]                  = column("wine_name", text.as[WineName])
  val vintage: Column[Option[Vintage]]            = column("vintage", int4.as[Vintage].opt)
  val color: Column[WineColor]                    = column("color", text.asEnum[WineColor])
  val region: Column[Region]                      = column("region", text.as[Region])
  val appellation: Column[Appellation]            = column("appellation", text.as[Appellation])
  val producerName: Column[ProducerName]          = column("producer_name", text.as[ProducerName])
  val bottleSize: Column[BottleSize]              = column("bottle_size", text.asEnum[BottleSize])
  val bottleCount: Column[BottleCount]            = column("bottle_count", int4.as[BottleCount])
  val description: Column[Option[Description]]    = column("description", text.as[Description].opt)
  val startingPrice: Column[Price]                = column("starting_price", numeric.as[Price])
  val currency: Column[Currency]                  = column("currency", text.imap(Currency.getInstance)(_.getCurrencyCode))
  val status: Column[AuctionStatus]               = column("status", text.asEnum[AuctionStatus])
  val startsAt: Column[Instant]                   = column("starts_at", timestamptz.asInstant)
  val endsAt: Column[Instant]                     = column("ends_at", timestamptz.asInstant)
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[UserId] = sellerId

  def mapping: (List[Column[?]], Codec[AuctionRow]) =
    (
      id,
      sellerId,
      wineName,
      vintage,
      color,
      region,
      appellation,
      producerName,
      bottleSize,
      bottleCount,
      description,
      startingPrice,
      currency,
      status,
      startsAt,
      endsAt,
      createdAt,
      updatedAt,
      deletedAt
    )
}

private[repositories] final case class AuctionRowFilter(
  id: SqlPredicate[AuctionId] = p.any,
  sellerId: SqlPredicate[UserId] = p.any,
  status: SqlPredicate[AuctionStatus] = p.any,
  endsAt: SqlPredicate[Instant] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull,
  page: Option[PageRequest[AuctionSortField]] = None)
    extends PageableSqlFilter[AuctionSortField] {
  override def filterFragment: AppliedFragment = fromPredicates(
    (
      id        -> AuctionRowTable.id,
      sellerId  -> AuctionRowTable.sellerId,
      status    -> AuctionRowTable.status,
      endsAt    -> AuctionRowTable.endsAt,
      deletedAt -> AuctionRowTable.deletedAt
    )
  )

  override protected def pageRequest: Option[PageRequest[AuctionSortField]] = page
  override protected def tieBreakColumn: Column[?]                          = AuctionRowTable.id

  override protected def sortColumnFor(field: AuctionSortField): Column[?] = field match {
    case AuctionSortField.CreatedAt     => AuctionRowTable.createdAt
    case AuctionSortField.EndsAt        => AuctionRowTable.endsAt
    case AuctionSortField.StartingPrice => AuctionRowTable.startingPrice
  }
}

class AuctionRepository {
  def save(auction: Auction): DB[Unit] =
    repository.create(AuctionRow(auction)).void

  def find(id: AuctionId): DB[Option[Auction]] =
    repository.findById(id).map(_.map(_.toAuction))

  def findForUpdate(id: AuctionId): DBInTransaction[Option[Auction]] =
    repository.findById(id, Lock.ForUpdate).map(_.map(_.toAuction))

  def list(
    status: Option[AuctionStatus],
    sellerId: Option[UserId],
    page: PageRequest[AuctionSortField]
  ): DB[(List[(Auction, Price)], Long)] = {
    val session = summon[Session[IO]]
    val filter  =
      AuctionRowFilter(status = status.fold(p.any[AuctionStatus])(p.equal), sellerId = sellerId.fold(p.any[UserId])(p.equal), page = Some(page))
    val applied   = filter.filterFragment
    val orderBy   = filter.orderByFragment
    val offLim    = filter.offsetLimitFragment
    val rowCodec  = AuctionRowTable.c ~ BidRowTable.amount.c
    val rowsQuery = sql"""
      SELECT ${AuctionRowTable.*("a")}, COALESCE(b.${BidRowTable.amount.n}, a.${AuctionRowTable.startingPrice.n})
      FROM ${AuctionRowTable.n} a
      LEFT JOIN LATERAL (
        SELECT ${BidRowTable.amount.n}
        FROM ${BidRowTable.n}
        WHERE ${BidRowTable.auctionId.n} = a.${AuctionRowTable.id.n}
        ORDER BY ${BidRowTable.amount.n} DESC
        LIMIT 1
      ) b ON TRUE
      WHERE ${applied.fragment}
      $orderBy
      ${offLim.fragment}
    """.query(rowCodec)
    for {
      rows  <- session.execute(rowsQuery)(applied.argument, offLim.argument)
      total <- repository.countByFilter(filter)
    } yield (rows.map { case (row, price) => (row.toAuction, price) }, total)
  }

  def listExpired(now: Instant): DB[List[AuctionId]] = {
    val filter = AuctionRowFilter(status = p.equal(AuctionStatus.Open), endsAt = p.lessThanOrEqual(now))
    repository.findByFilter(filter).map(_.map(_.id))
  }

  def listOpenBySeller(sellerId: UserId): DB[List[Auction]] = {
    val filter = AuctionRowFilter(sellerId = p.equal(sellerId), status = p.equal(AuctionStatus.Open))
    repository.findByFilter(filter).map(_.map(_.toAuction))
  }

  def update[E](id: AuctionId, f: Auction => Either[E, Auction]): DBInTransaction[Option[Either[E, Auction]]] =
    findForUpdate(id).flatMap {
      case None          => IO.pure(None)
      case Some(auction) =>
        f(auction) match {
          case Left(e)        => IO.pure(Some(Left(e)))
          case Right(updated) => persist(updated).as(Some(Right(updated)))
        }
    }

  def softDelete(id: AuctionId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  private def persist(auction: Auction): DB[Unit] =
    repository.update(AuctionRow(auction))

  private val repository: IdRepository[AuctionRow, AuctionId] & SoftDeleteRepository[AuctionRow, AuctionId] & ForeignIdRepository[
    AuctionRow,
    UserId
  ] & FilteringRepository[AuctionRow, AuctionRowFilter] =
    new IdRepository[AuctionRow, AuctionId](_.id)
      with SoftDeleteRepository[AuctionRow, AuctionId]
      with ForeignIdRepository[AuctionRow, UserId]
      with FilteringRepository[AuctionRow, AuctionRowFilter] {
      override val table: AuctionRowTable.type = AuctionRowTable
    }
}
