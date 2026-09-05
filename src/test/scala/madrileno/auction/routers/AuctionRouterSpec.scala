package madrileno.auction.routers

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.auction.routers.dto.*
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.{User, UserId}
import madrileno.utils.db.transactor.DB
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.pagination.{Cursor, Page, SortDirection}
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.{EntityDecoder, Request, Uri}
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

import java.time.Instant
import java.util.Currency

class AuctionRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private val eur = Currency.getInstance("EUR")

  private val auctionRepository = new AuctionRepository
  private val bidRepository     = new BidRepository

  private val seller     = TestData.user()
  private val bidder     = TestData.user()
  private val other      = TestData.user()
  private val sellerAuth = AuthContext(seller)
  private val bidderAuth = AuthContext(bidder)
  private val otherAuth  = AuthContext(other)

  private def seedUser(user: User): DB[User] =
    application.userRepository.find(user.id).flatMap {
      case Some(existing) => IO.pure(existing)
      case None           => application.userRepository.create(user, Instant.now())
    }

  private def seedAuction(
    id: AuctionId,
    sellerId: UserId,
    status: AuctionStatus = AuctionStatus.Open,
    startsAt: Instant = Instant.now().minusSeconds(60),
    endsAt: Instant = Instant.now().plusSeconds(3600)
  ): DB[Auction] = {
    val auction = TestData.auction(id = id, sellerId = sellerId, status = status, startsAt = startsAt, endsAt = endsAt)
    auctionRepository.save(auction).as(auction)
  }

  private def seedBid(
    auctionId: AuctionId,
    bidderId: UserId,
    amount: Price
  ): DB[Bid] = {
    val bid = TestData.bid(auctionId = auctionId, bidderId = bidderId, amount = amount)
    bidRepository.save(bid)
  }

  // Setup helpers — run inside `withSetup` so each scenario gets a fresh, self-contained AuctionId
  private def setupAuction(
    status: AuctionStatus = AuctionStatus.Open,
    startsAt: Instant = Instant.now().minusSeconds(60),
    endsAt: Instant = Instant.now().plusSeconds(3600),
    alsoSeedBidder: Boolean = false,
    alsoSeedOther: Boolean = false
  ): Auction = {
    val id = TestData.randomAuctionId()
    application.transactor
      .inSession {
        seedUser(seller) *>
          (if (alsoSeedBidder) seedUser(bidder).void else IO.unit) *>
          (if (alsoSeedOther) seedUser(other).void else IO.unit) *>
          seedAuction(id, seller.id, status = status, startsAt = startsAt, endsAt = endsAt)
      }
      .unsafeRunSync()
  }

  private def setupAuctionWithExistingBid(amount: Price): Auction = {
    val id = TestData.randomAuctionId()
    application.transactor
      .inSession {
        seedUser(seller) *> seedUser(bidder) *>
          seedAuction(id, seller.id).flatTap(_ => seedBid(id, bidder.id, amount))
      }
      .unsafeRunSync()
  }

  private def setupAuctionWithTwoBids(): Auction = {
    val id    = TestData.randomAuctionId()
    val older = TestData.bid(auctionId = id, bidderId = bidder.id, amount = Price(BigDecimal(200)), createdAt = Instant.parse("2026-01-01T00:00:01Z"))
    val newer = TestData.bid(auctionId = id, bidderId = other.id, amount = Price(BigDecimal(210)), createdAt = Instant.parse("2026-01-01T00:00:02Z"))
    application.transactor
      .inSession {
        seedUser(seller) *> seedUser(bidder) *> seedUser(other) *>
          seedAuction(id, seller.id).flatTap(_ => bidRepository.save(older) *> bidRepository.save(newer))
      }
      .unsafeRunSync()
  }

  private def cancelRequest(auth: AuthContext)(auction: Auction) =
    onRequest(security = bearer.apply(validJwt(auth)), pathParameters = auction.id)

  private def placeBidRequest(amount: BigDecimal, auth: AuthContext)(auction: Auction) =
    onRequest(body = PlaceBidRequest(Price(amount)), security = bearer.apply(validJwt(auth)), pathParameters = auction.id)

  private def sampleCreateRequest(startsAt: Instant = Instant.now().minusSeconds(60), endsAt: Instant = Instant.now().plusSeconds(3600)) =
    CreateAuctionRequest(
      wineName = WineName("Château Margaux"),
      vintage = Some(Vintage(2015)),
      color = WineColor.Red,
      region = Region("Bordeaux"),
      appellation = Appellation("Margaux"),
      producerName = ProducerName("Château Margaux"),
      bottleSize = BottleSize.Standard,
      bottleCount = BottleCount(1),
      description = Some(Description("A fine Bordeaux")),
      startingPrice = Price(BigDecimal(100)),
      currency = eur,
      startsAt = startsAt,
      endsAt = endsAt
    )

  path("/v1/auctions")(
    supports(
      POST,
      description = "Create a new auction",
      summary = "Authenticated seller opens a new wine auction",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Auctions")
    )(
      withSetup {
        application.transactor.inSession(seedUser(seller).void).unsafeRunSync()
      }.request(_ => onRequest(body = sampleCreateRequest(), security = bearer.apply(validJwt(sellerAuth))))
        .respondsWith[AuctionDto](Created, description = "Auction created")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.sellerId shouldBe seller.id
          response.body.status shouldBe AuctionStatus.Open
          response.body.currentPrice shouldBe Price(BigDecimal(100))
        },
      onRequest(
        body = sampleCreateRequest(startsAt = Instant.now().plusSeconds(60), endsAt = Instant.now()),
        security = bearer.apply(validJwt(sellerAuth))
      ).respondsWith[Error[Unit]](BadRequest, description = "Invalid auction window")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.exists(_.startsWith("Auction window is invalid")) shouldBe true
        }
    ),
    supports(
      GET,
      description =
        "List auctions (paginated). Filter by status / seller; page with `limit` (1–100, default 20) + `offset`; sort by `CreatedAt` / `EndsAt` / `StartingPrice`, `Asc` / `Desc` (default `CreatedAt` `Desc`), with `id` as a stable tie-break.",
      summary = "Unauthenticated: a page of auctions, optionally filtered and sorted",
      queryParameters =
        (
          q[Option[AuctionStatus]]("status", "Filter by auction status"),
          q[Option[UserId]]("seller-id", "Filter by seller id"),
          q[Option[AuctionSortField]]("sort-by", "Sort field — CreatedAt | EndsAt | StartingPrice (default CreatedAt)"),
          q[Option[SortDirection]]("sort-dir", "Sort direction — Asc | Desc (default Desc)"),
          q[Option[Int]]("limit", "Page size, 1–100 (default 20; out-of-range values are clamped)"),
          q[Option[Int]]("offset", "Rows to skip (default 0)")
        ),
      tags = Seq("Auctions")
    )(
      withSetup {
        val seller = TestData.user()
        val _      = application.transactor
          .inSession(
            seedUser(seller) *> seedAuction(TestData.randomAuctionId(), seller.id) *> seedAuction(TestData.randomAuctionId(), seller.id) *>
              seedAuction(TestData.randomAuctionId(), seller.id)
          )
          .unsafeRunSync()
        seller
      }.request(seller =>
        onRequest(queryParameters = (None, Some(seller.id), Some(AuctionSortField.CreatedAt), Some(SortDirection.Desc), Some(2), Some(0)))
      ).respondsWith[Page[AuctionDto]](Ok, description = "A page of auctions")
        .assert { case (ctx, seller) =>
          val response = ctx.performRequest(allRoutes)
          response.body.total shouldBe 3L
          response.body.limit shouldBe 2
          response.body.offset shouldBe 0
          response.body.items.size shouldBe 2
          response.body.items.map(_.sellerId).toSet shouldBe Set(seller.id)
        },
      onRequest(queryParameters = (None, None, Some(AuctionSortField.CreatedAt), Some(SortDirection.Desc), Some(999), Some(-3)))
        .respondsWith[Page[AuctionDto]](Ok, description = "Out-of-range limit/offset are clamped, not rejected")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.limit shouldBe 100
          response.body.offset shouldBe 0
        }
    )
  )

  path("/v1/auctions/stream")(
    supports(
      GET,
      description = """Live auction event stream over WebSocket — the firehose (every auction).
          |
          |Connect with a WebSocket client to receive a JSON frame per event. Wire format:
          |`{"kind": "...", "data": {...}}` where `kind` is one of `AuctionCreated`, `BidPlaced`,
          |`AuctionCancelled`, `AuctionClosed` and `data` is the per-variant flat DTO. See
          |`docs/adding-a-module.md` for the wire shapes.
          |
          |For a single auction's events, connect to `/v1/auctions/{auctionId}/stream` instead.
          |
          |OpenAPI 3.x does not model WebSockets; this entry exists for discoverability only.
          |A WebSocket upgrade request returns 101 Switching Protocols and starts streaming.
          |A plain GET (no `Upgrade` header) returns 426 Upgrade Required with the body
          |`Upgrade required for WebSocket communication.` — documented below.""".stripMargin,
      summary = "Live auction event stream (WebSocket)",
      tags = Seq("Auctions")
    )(
      onRequest()
        // Override circe's String entity decoder so the plain-text 426 body decodes via http4s' text decoder.
        .respondsWith[String](UpgradeRequired, description = "Plain GET fallback — non-upgrade response")(
          using EntityDecoder.text[IO],
          summon,
          summon
        )
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body shouldBe "Upgrade required for WebSocket communication."
        }
    )
  )

  path("/v1/auctions/{auctionId}/stream")(
    supports(
      GET,
      description = """Live event stream for a single auction over WebSocket — same wire format as `/v1/auctions/stream`,
          |but only `AuctionCreated`/`BidPlaced`/`AuctionCancelled`/`AuctionClosed` events for this `auctionId`.
          |
          |OpenAPI 3.x does not model WebSockets; this entry exists for discoverability only. A plain GET
          |(no `Upgrade` header) returns 426 Upgrade Required with the body `Upgrade required for WebSocket communication.`""".stripMargin,
      summary = "Live event stream for one auction (WebSocket)",
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      onRequest(pathParameters = TestData.randomAuctionId())
        .respondsWith[String](UpgradeRequired, description = "Plain GET fallback — non-upgrade response")(
          using EntityDecoder.text[IO],
          summon,
          summon
        )
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body shouldBe "Upgrade required for WebSocket communication."
        }
    )
  )

  path("/v1/auctions/{auctionId}")(
    supports(
      GET,
      description = "Get an auction by id",
      summary = "Unauthenticated: returns an auction with its current price",
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      withSetup(setupAuction())
        .request(auction => onRequest(pathParameters = auction.id))
        .respondsWith[AuctionDto](Ok, description = "Auction found")
        .assert { case (ctx, auction) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe auction.id
          response.body.sellerId shouldBe seller.id
        },
      onRequest(pathParameters = TestData.randomAuctionId())
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        }
    ),
    supports(
      DELETE,
      description = "Cancel an auction",
      summary = "Seller-only: cancels an open auction",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      withSetup(setupAuction())
        .request(cancelRequest(sellerAuth))
        .respondsWith[EmptyBody](NoContent, description = "Auction cancelled")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = TestData.randomAuctionId())
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      withSetup(setupAuction(alsoSeedOther = true))
        .request(cancelRequest(otherAuth))
        .respondsWith[Error[Unit]](Forbidden, description = "Cancel attempted by non-owner")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can cancel this auction")
        },
      withSetup(setupAuction(status = AuctionStatus.Cancelled))
        .request(cancelRequest(sellerAuth))
        .respondsWith[Error[Unit]](Conflict, description = "Auction already cancelled or closed")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction is not open")
        }
    )
  )

  path("/v1/auctions/{auctionId}/bids")(
    supports(
      GET,
      description = "List the bid history for an auction, newest-first, cursor-paginated by bid id.",
      summary = "Unauthenticated: bids newest-first; bidder identity is a per-auction pseudonym (bidderRef)",
      pathParameters = p[AuctionId]("auctionId"),
      queryParameters = (
        q[Option[Int]]("limit", "Page size, 1–100 (default 20; out-of-range values are clamped)"),
        q[Option[BidId]]("after-id", "Cursor: return bids older than this bid id (omit for the first page)")
      ),
      tags = Seq("Auctions")
    )(
      withSetup(setupAuctionWithTwoBids())
        .request(auction => onRequest(pathParameters = auction.id, queryParameters = (Some(1), None)))
        .respondsWith[Cursor[BidHistoryEntryDto]](Ok, description = "A page of bid history (newest first)")
        .assert { case (ctx, auction) =>
          val page1 = ctx.performRequest(allRoutes)
          page1.body.items.map(_.amount) shouldBe List(Price(BigDecimal(210)))
          page1.body.items.map(_.bidderRef.unwrap).foreach(_ should not be empty)
          page1.body.items.map(_.currency).toSet shouldBe Set(Currency.getInstance("EUR"))
          page1.body.hasMore shouldBe true

          val afterId = page1.body.items.last.id
          val page2   = allRoutes.orNotFound
            .run(Request[IO](GET, Uri.unsafeFromString(s"/v1/auctions/${auction.id}/bids?limit=1&after-id=$afterId")))
            .unsafeRunSync()
            .as[Cursor[BidHistoryEntryDto]]
            .unsafeRunSync()
          page2.items.map(_.amount) shouldBe List(Price(BigDecimal(200)))
          page2.hasMore shouldBe false
        },
      onRequest(pathParameters = TestData.randomAuctionId(), queryParameters = (None, None))
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        }
    ),
    supports(
      POST,
      description = "Place a bid on an auction",
      summary = "Authenticated: places a bid above the current highest",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      withSetup(setupAuction(alsoSeedBidder = true))
        .request(placeBidRequest(150, bidderAuth))
        .respondsWith[BidDto](Created, description = "Bid placed")
        .assert { case (ctx, auction) =>
          val response = ctx.performRequest(allRoutes)
          response.body.amount shouldBe Price(BigDecimal(150))
          response.body.bidderId shouldBe bidder.id
          response.body.auctionId shouldBe auction.id
        },
      onRequest(
        body = PlaceBidRequest(Price(BigDecimal(150))),
        security = bearer.apply(validJwt(bidderAuth)),
        pathParameters = TestData.randomAuctionId()
      ).respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      withSetup(setupAuction(status = AuctionStatus.Cancelled, alsoSeedBidder = true))
        .request(placeBidRequest(150, bidderAuth))
        .respondsWith[Error[Unit]](Conflict, description = "Auction is not open")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction is not open")
        },
      withSetup(setupAuction(startsAt = Instant.now().plusSeconds(600), endsAt = Instant.now().plusSeconds(3600), alsoSeedBidder = true))
        .request(placeBidRequest(150, bidderAuth))
        .respondsWith[Error[Unit]](Conflict, description = "Auction has not started yet")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction has not started yet")
        },
      withSetup(setupAuction())
        .request(placeBidRequest(150, sellerAuth))
        .respondsWith[Error[Unit]](Forbidden, description = "Cannot bid on own auction")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Cannot bid on your own auction")
        },
      withSetup(setupAuction(alsoSeedBidder = true))
        .request(placeBidRequest(50, bidderAuth))
        .respondsWith[Error[Unit]](Conflict, description = "Bid below minimum")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Bid is below the current minimum")
        },
      withSetup(setupAuctionWithExistingBid(Price(BigDecimal(200))))
        .request(placeBidRequest(250, bidderAuth))
        .respondsWith[Error[Unit]](Conflict, description = "Already the highest bidder")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("You already have the highest bid")
        }
    )
  )
}
