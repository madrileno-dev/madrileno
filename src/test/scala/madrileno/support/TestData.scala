package madrileno.support

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.IpAddress
import io.circe.Json
import madrileno.auction.domain.*
import madrileno.auth.domain.{AuthContext, *}
import madrileno.ticket.domain.TicketId
import madrileno.user.domain.*
import madrileno.utils.crypto.{RandomSecret, UuidV7}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.storage.StorageKey
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`

import java.net.URI
import java.time.{Duration, Instant}
import java.util.{Currency, UUID}

object TestData {
  // Mint via the same time-ordered UUIDv7 path as production (IdGenerator), so fixtures mirror real ids — never raw v4.
  def randomUuid(): UUID = UuidV7.generate.unsafeRunSync()

  def randomUserId(): UserId                             = UserId(randomUuid())
  def randomRefreshTokenId(): RefreshTokenId             = RefreshTokenId(randomUuid())
  def randomRefreshTokenFamilyId(): RefreshTokenFamilyId = RefreshTokenFamilyId(randomUuid())
  def refreshTokenSecret(): RefreshTokenSecret           =
    RandomSecret.generate(RefreshTokenSecret.byteLength)(using TestGivens.secureRandom).map(RefreshTokenSecret.apply).unsafeRunSync()
  def randomUserAuthId(): UserAuthId = UserAuthId(randomUuid())
  def randomFlagId(): FlagId         = FlagId(randomUuid())
  def randomRuleId(): RuleId         = RuleId(randomUuid())
  def randomSegmentId(): SegmentId   = SegmentId(randomUuid())
  def randomTicketId(): TicketId     = TicketId(randomUuid())
  // scripts:scaffold-id-factories (scaffold-module.scala inserts random<Aggregate>Id() factories above this line)
  // scripts:auction-block-start
  def randomAuctionId(): AuctionId                         = AuctionId(randomUuid())
  def randomBidId(): BidId                                 = BidId(randomUuid())
  def randomAuctionImageId(): AuctionImageId               = AuctionImageId(randomUuid())
  def randomAuctionImageVariantId(): AuctionImageVariantId = AuctionImageVariantId(randomUuid())
  // scripts:auction-block-end

  def authContext(): AuthContext = AuthContext(user = user())

  def user(
    id: UserId = randomUserId(),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${randomUuid()}@example.com")),
    emailVerified: Boolean = true,
    avatarUrl: Option[URI] = None,
    blockedAt: Option[Instant] = None
  ): User = User(id, fullName, emailAddress, emailVerified, avatarUrl, blockedAt)

  def refreshToken(
    id: RefreshTokenId = randomRefreshTokenId(),
    familyId: RefreshTokenFamilyId = randomRefreshTokenFamilyId(),
    familyCreatedAt: Instant = Instant.now(),
    secretHash: RefreshTokenSecretHash = refreshTokenSecret().hash,
    userId: UserId = randomUserId(),
    userAgent: UserAgent = UserAgent("test-agent"),
    ipAddress: IpAddress = IpAddress.fromString("127.0.0.1").get,
    createdAt: Instant = Instant.now(),
    usedAt: Option[Instant] = None,
    successor: Option[SealedRefreshTokenSecret] = None,
    deletedAt: Option[Instant] = None,
    expiresAt: Instant = Instant.now().plus(Duration.ofDays(90))
  ): RefreshToken =
    RefreshToken(id, familyId, familyCreatedAt, secretHash, userId, userAgent, ipAddress, createdAt, usedAt, successor, deletedAt, expiresAt)

  def issuedRefreshToken(
    userId: UserId = randomUserId(),
    familyId: RefreshTokenFamilyId = randomRefreshTokenFamilyId(),
    familyCreatedAt: Instant = Instant.now(),
    userAgent: UserAgent = UserAgent("test-agent"),
    createdAt: Instant = Instant.now(),
    usedAt: Option[Instant] = None
  ): IssuedRefreshToken = {
    val secret = refreshTokenSecret()
    IssuedRefreshToken(
      refreshToken(
        familyId = familyId,
        familyCreatedAt = familyCreatedAt,
        secretHash = secret.hash,
        userId = userId,
        userAgent = userAgent,
        createdAt = createdAt,
        usedAt = usedAt
      ),
      secret
    )
  }

  def verifiedExternalToken(
    provider: Provider = Provider.Firebase,
    providerUserId: ProviderUserId = ProviderUserId(s"firebase-${randomUuid()}"),
    credential: Credential = Credential("test-credential"),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${randomUuid()}@example.com")),
    emailVerified: Boolean = true
  ): VerifiedExternalToken =
    VerifiedExternalToken(provider, providerUserId, credential, ExternalProfile(fullName, emailAddress, emailVerified, None), Metadata(Json.obj()))

  val defaultIpAddress: IpAddress = IpAddress.fromString("127.0.0.1").get

  def featureFlag(
    id: FlagId = randomFlagId(),
    key: FlagKey = FlagKey(s"test-flag-${randomUuid()}"),
    description: FlagDescription = FlagDescription(""),
    enabled: Boolean = true,
    defaultValue: FlagVariant = FlagVariant.BoolVariant(false),
    clientExposed: Boolean = false,
    rules: List[Rule] = Nil,
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now()
  ): FeatureFlag = FeatureFlag(id, key, description, enabled, defaultValue, clientExposed, rules, createdAt, updatedAt)

  def flagRule(
    id: RuleId = randomRuleId(),
    position: RulePosition = RulePosition(0),
    description: FlagDescription = FlagDescription(""),
    conditions: List[RuleCondition] = Nil,
    outcome: RuleOutcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(true)),
    createdAt: Instant = Instant.now()
  ): Rule = Rule(id, position, description, conditions, outcome, createdAt)

  def flagSegment(
    id: SegmentId = randomSegmentId(),
    name: SegmentName = SegmentName(s"test-segment-${randomUuid()}"),
    description: FlagDescription = FlagDescription(""),
    conditions: List[RuleCondition] = Nil,
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now()
  ): Segment = Segment(id, name, description, conditions, createdAt, updatedAt)

  // scripts:auction-block-start
  def auction(
    id: AuctionId = randomAuctionId(),
    sellerId: UserId = randomUserId(),
    wineName: WineName = WineName("Château Margaux"),
    vintage: Option[Vintage] = Some(Vintage(2015)),
    color: WineColor = WineColor.Red,
    region: Region = Region("Bordeaux"),
    appellation: Appellation = Appellation("Margaux"),
    producerName: ProducerName = ProducerName("Château Margaux"),
    bottleSize: BottleSize = BottleSize.Standard,
    bottleCount: BottleCount = BottleCount(1),
    description: Option[Description] = Some(Description("A fine wine")),
    startingPrice: Price = Price(BigDecimal(100)),
    currency: Currency = Currency.getInstance("EUR"),
    status: AuctionStatus = AuctionStatus.Open,
    startsAt: Instant = Instant.now(),
    endsAt: Instant = Instant.now().plusSeconds(3600),
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now(),
    deletedAt: Option[Instant] = None
  ): Auction = Auction(
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

  def bid(
    auctionId: AuctionId = randomAuctionId(),
    bidderId: UserId = randomUserId(),
    amount: Price = Price(BigDecimal(150)),
    createdAt: Instant = Instant.now(),
    id: Option[BidId] = None
  ): Bid = Bid(id.getOrElse(BidId(UuidV7.fromParts(createdAt.toEpochMilli, randomUuid()))), auctionId, bidderId, amount, createdAt)

  def auctionImage(
    id: AuctionImageId = randomAuctionImageId(),
    auctionId: AuctionId = randomAuctionId(),
    storageKey: StorageKey = StorageKey(s"auctions/test/images/${randomUuid()}"),
    fileName: String = "wine.jpg",
    contentType: `Content-Type` = `Content-Type`(MediaType.image.jpeg),
    sizeBytes: SizeBytes = SizeBytes(1024L),
    position: ImagePosition = ImagePosition(0),
    uploadedAt: Instant = Instant.now(),
    deletedAt: Option[Instant] = None,
    width: Option[Width] = None,
    height: Option[Height] = None,
    format: Option[ImageFormat] = None,
    analyzedAt: Option[Instant] = None
  ): AuctionImage =
    AuctionImage(id, auctionId, storageKey, fileName, contentType, sizeBytes, position, uploadedAt, deletedAt, width, height, format, analyzedAt)
  // scripts:auction-block-end
}
