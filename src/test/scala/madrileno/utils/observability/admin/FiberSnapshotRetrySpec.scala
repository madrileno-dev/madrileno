package madrileno.utils.observability.admin

import cats.effect.unsafe.implicits.global
import cats.effect.{FiberSnapshot, IO, Ref}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import retry.RetryPolicies.limitRetries

class FiberSnapshotRetrySpec extends AnyFunSpec with Matchers {

  private val handoffRace = new NullPointerException("this.fiberBag is null")

  private def snapshotFailing(times: Int): IO[(Ref[IO, Int], IO[FiberSnapshot])] =
    Ref.of[IO, Int](0).map { calls =>
      val take = calls.updateAndGet(_ + 1).flatMap { call =>
        if (call <= times) IO.raiseError(handoffRace) else IO.pure(FiberSnapshot.empty)
      }
      (calls, take)
    }

  describe("ThreaddumpAdminRouter.retryingOnHandoff") {
    it("returns the snapshot once an attempt wins the race") {
      val (calls, snapshot) = snapshotFailing(times = 2).unsafeRunSync()
      ThreaddumpAdminRouter.retryingOnHandoff(snapshot, limitRetries[IO](4)).unsafeRunSync() shouldBe FiberSnapshot.empty
      calls.get.unsafeRunSync() shouldBe 3
    }

    it("fails with the last race after the configured number of attempts") {
      val (calls, snapshot) = snapshotFailing(times = Int.MaxValue).unsafeRunSync()
      val result            = ThreaddumpAdminRouter.retryingOnHandoff(snapshot, limitRetries[IO](3)).attempt.unsafeRunSync()
      result shouldBe Left(handoffRace)
      calls.get.unsafeRunSync() shouldBe 4
    }

    it("lets any other failure through untouched") {
      val broken = new IllegalStateException("not a handoff race")
      val result = ThreaddumpAdminRouter.retryingOnHandoff(IO.raiseError(broken), limitRetries[IO](4)).attempt.unsafeRunSync()
      result shouldBe Left(broken)
    }
  }
}
