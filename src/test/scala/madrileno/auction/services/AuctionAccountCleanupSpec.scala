package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.user.domain.{User, UserAccountDeleted}
import madrileno.user.repositories.UserRepository
import madrileno.user.services.UserService
import madrileno.utils.events.bus.EventBusRuntime
import madrileno.utils.events.outbox.*
import madrileno.utils.mailer.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.net.URI
import java.time.Instant
import scala.concurrent.duration.*

class AuctionAccountCleanupSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext    = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private given Clock[IO]   = TestGivens.fixedClock()
  private given UUIDGen[IO] = TestGivens.deterministicUUIDs()

  private lazy val userRepo     = new UserRepository
  private lazy val auctionRepo  = new AuctionRepository
  private lazy val bidRepo      = new BidRepository
  private lazy val outboxRepo   = new OutboxRepository
  private lazy val deliveryRepo = new OutboxDeliveryRepository

  private lazy val scheduler  = Scheduler(transactor, SchedulerConfig())
  private lazy val smtpSender = SmtpSender(MailerConfig(host = "localhost", port = 25, fromAddress = "test@example.com", tls = false))
  private lazy val mailer     = new Mailer(smtpSender, scheduler.client, MailContext(baseUrl = URI("https://example.com")))

  private lazy val auctionEventBus = EventBusRuntime.local.topic[AuctionEvent]("auction_events_cleanup_test", maxQueued = 64)

  private lazy val service = new AuctionAccountCleanupService(auctionRepo, bidRepo, userRepo, mailer, auctionEventBus)

  private final case class Fixtures(
    seller: User,
    openAuction: Auction,
    closedAuction: Auction)

  private def seedAll: IO[Fixtures] = {
    val seller          = TestData.user()
    val bidder          = TestData.user()
    val outsider        = TestData.user()
    val openAuction     = TestData.auction(sellerId = seller.id)
    val closedAuction   = TestData.auction(sellerId = seller.id, status = AuctionStatus.Closed)
    val outsiderAuction = TestData.auction(sellerId = outsider.id)
    val seedBid         = TestData.bid(auctionId = openAuction.id, bidderId = bidder.id)
    transactor
      .inTransaction {
        val now = Instant.now()
        userRepo.create(seller, now) *>
          userRepo.create(bidder, now) *>
          userRepo.create(outsider, now) *>
          auctionRepo.save(openAuction) *>
          auctionRepo.save(closedAuction) *>
          auctionRepo.save(outsiderAuction) *>
          bidRepo.save(seedBid).void
      }
      .as(Fixtures(seller, openAuction, closedAuction))
  }

  private def sendMailTaskCount: IO[Long] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.unique(sql"SELECT count(*) FROM scheduled_task WHERE task_name = $text".query(int8))("send-mail")
    }

  "AuctionAccountCleanupService.onAccountDeleted" should {
    "cancels open auctions, skips closed ones, publishes the live event, and enqueues bidder notifications" in {
      for {
        fx                <- seedAll
        before            <- sendMailTaskCount
        (reaction, event) <- auctionEventBus.subscribeAwait
                               .use { stream =>
                                 for {
                                   subFiber <- stream.take(1).compile.lastOrError.start
                                   reaction <- transactor.inTransaction(service.onAccountDeleted(UserAccountDeleted(fx.seller.id)))
                                   event    <- subFiber.joinWithNever
                                 } yield (reaction, event)
                               }
                               .timeout(5.seconds)
        open   <- transactor.inSession(auctionRepo.find(fx.openAuction.id))
        closed <- transactor.inSession(auctionRepo.find(fx.closedAuction.id))
        after  <- sendMailTaskCount
      } yield {
        reaction shouldBe Reaction.Done
        event.auctionId shouldBe fx.openAuction.id
        event shouldBe a[AuctionEvent.AuctionCancelled]
        open.map(_.status) shouldBe Some(AuctionStatus.Cancelled)
        closed.map(_.status) shouldBe Some(AuctionStatus.Closed)
        (after - before) shouldBe 1L
      }
    }

    "full flow: deleteAccount publishes, delivery cancels the auction and completes the ledger" in {
      val auctionSub = OutboxSubscription[UserAccountDeleted]("auction")(service.onAccountDeleted)
      val dispatcher = new OutboxDispatcher(List(auctionSub), outboxRepo, deliveryRepo, transactor, OutboxConfig())
      val outbox     = new Outbox(outboxRepo, deliveryRepo, scheduler.client, dispatcher)

      val userService = new UserService(userRepo, outbox, transactor)

      def runDelivery(
        d: OutboxDispatcher,
        consumer: String,
        eventId: DomainEventId
      ): IO[Unit] = {
        val task = d.deliveryTasks.find(_.descriptor.taskName == OutboxDeliveryTasks.taskName(consumer)).get
        task.execution(d.instanceFor(consumer, eventId))
      }

      for {
        fx     <- seedAll
        _      <- userService.deleteAccount(fx.seller.id)
        events <- transactor.inSession(outboxRepo.findByAggregate("user", fx.seller.id.unwrap))
        id = events.head.id
        st0  <- transactor.inTransaction(deliveryRepo.lockForDelivery(id, "auction"))
        _    <- runDelivery(dispatcher, "auction", id)
        st1  <- transactor.inTransaction(deliveryRepo.lockForDelivery(id, "auction"))
        open <- transactor.inSession(auctionRepo.find(fx.openAuction.id))
      } yield {
        st0 shouldBe Some(DeliveryStatus.Pending)
        st1 shouldBe Some(DeliveryStatus.Completed)
        open.map(_.status) shouldBe Some(AuctionStatus.Cancelled)
      }
    }
  }
}
