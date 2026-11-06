package madrileno.utils.db.transactor

import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import madrileno.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.exception.PostgresErrorException
import skunk.implicits.*

import java.net.ServerSocket
import scala.concurrent.duration.*

class PgTransactorLimitsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val one: Query[Void, Int]                 = sql"SELECT 1".query(int4)
  private val sleepTenSeconds: Query[Void, Int]     = sql"SELECT 1 FROM pg_sleep(10)".query(int4)
  private val backendPid: Query[Void, Int]          = sql"SELECT pg_backend_pid()".query(int4)
  private val statementTimeout: Query[Void, String] = sql"SHOW statement_timeout".query(text)
  private val terminate: Query[Int, Boolean]        = sql"SELECT pg_terminate_backend($int4)".query(bool)
  private val lockRow: Query[Void, Int]             = sql"SELECT id FROM limits_probe WHERE id = 1 FOR UPDATE".query(int4)

  private def run[A](query: Query[Void, A]): DB[A] = summon[Session[IO]].unique(query)

  private def exec(command: Command[Void]): DB[Unit] = summon[Session[IO]].execute(command).void

  private def limited(
    name: String,
    max: Int = 1,
    acquireTimeout: Duration = 5.seconds,
    statementTimeout: Duration = 30.seconds,
    idleInTransactionTimeout: Duration = 1.minute
  ): Resource[IO, PgTransactor] =
    transactorWith(
      _.copy(
        max = max,
        acquireTimeout = acquireTimeout,
        statementTimeout = statementTimeout,
        idleInTransactionTimeout = idleInTransactionTimeout,
        parameters = Session.DefaultConnectionParameters + ("application_name" -> name)
      )
    )

  private def holdingOne(transactor: Transactor): Resource[IO, Unit] =
    Resource.eval(Deferred[IO, Unit]).flatMap { taken =>
      transactor.inSession(taken.complete(()) *> IO.never).background.evalMap(_ => taken.get.timeout(5.seconds))
    }

  private def holding(transactor: Transactor, sessions: Int): Resource[IO, Unit] =
    List.fill(sessions)(holdingOne(transactor)).sequence_

  private def sqlState(result: Either[Throwable, ?]): Option[String] =
    result.left.toOption.collect { case e: PostgresErrorException => e.code }

  private def unavailable(result: Either[Throwable, ?]): Boolean =
    result.left.toOption.exists(DatabaseUnavailable.unapply)

  "The statement timeout" should {
    "cancel a query that runs past it" in {
      limited("statement-timeout-cancels", statementTimeout = 300.millis).use { db =>
        db.inSession(run(sleepTenSeconds)).attempt.timed.map { case (took, result) =>
          sqlState(result) shouldBe Some("57014")
          unavailable(result) shouldBe true
          took should be < 5.seconds
        }
      }
    }

    "leave the connection that hit it usable for the next request" in {
      limited("statement-timeout-reuse", statementTimeout = 300.millis).use { db =>
        for {
          before   <- db.inSession(run(backendPid))
          canceled <- db.inSession(run(sleepTenSeconds)).attempt
          after    <- db.inSession(run(backendPid))
          answer   <- db.inSession(run(one))
        } yield {
          sqlState(canceled) shouldBe Some("57014")
          after shouldBe before
          answer shouldBe 1
        }
      }
    }

    "come back after a request changed it for its own session" in {
      limited("statement-timeout-reset", statementTimeout = 300.millis).use { db =>
        for {
          _       <- db.inSession(exec(sql"SET statement_timeout = 0".command))
          current <- db.inSession(run(statementTimeout))
        } yield current shouldBe "300ms"
      }
    }

    "end a wait for a row lock another request holds" in {
      limited("statement-timeout-lock", max = 2, statementTimeout = 500.millis).use { db =>
        for {
          _       <- withSession(exec(sql"CREATE TABLE IF NOT EXISTS limits_probe (id int PRIMARY KEY)".command))
          _       <- withSession(exec(sql"INSERT INTO limits_probe VALUES (1) ON CONFLICT DO NOTHING".command))
          locked  <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          holder  <- db.inTransaction(run(lockRow) *> locked.complete(()) *> release.get).start
          _       <- locked.get
          waited  <- db.inSession(exec(sql"UPDATE limits_probe SET id = 1 WHERE id = 1".command)).attempt.timed
          _       <- release.complete(()) *> holder.joinWithNever
        } yield {
          val (took, result) = waited
          sqlState(result) shouldBe Some("57014")
          took should be < 5.seconds
        }
      }
    }
  }

  "The idle-in-transaction timeout" should {
    "let the server end a transaction left idle, after which the pool connects again" in {
      limited("idle-in-transaction", idleInTransactionTimeout = 300.millis).use { db =>
        for {
          before <- db.inSession(run(backendPid))
          idle   <- db.inTransaction(run(one) *> IO.sleep(1.second) *> run(one)).attempt
          after  <- db.inSession(run(backendPid))
        } yield {
          idle.left.toOption.map(_.getClass) shouldBe Some(classOf[TransactionEndedWhileIdle])
          idle.left.exists(DatabaseUnavailable.unapply) shouldBe false
          after should not be before
        }
      }
    }

    "leave a dropped connection in a short transaction to the database-unavailable path" in {
      limited("dropped-in-transaction", idleInTransactionTimeout = 1.minute).use { db =>
        for {
          dropped <- db.inTransaction(run(backendPid).flatMap(pid => withSession(summon[Session[IO]].unique(terminate)(pid))) *> run(one)).attempt
        } yield dropped.left.exists(DatabaseUnavailable.unapply) shouldBe true
      }
    }
  }

  "The acquire timeout" should {
    "fail a request that waits longer than it for a free connection" in {
      limited("acquire-timeout", max = 2, acquireTimeout = 300.millis).use { db =>
        holding(db, 2).surround(db.inSession(run(one)).attempt.timed).map { case (took, result) =>
          result.left.toOption.map(_.getClass) shouldBe Some(classOf[DatabaseUnavailable])
          took should be >= 250.millis
          took should be < 3.seconds
        }
      }
    }

    "hand a connection freed within it to the waiting request" in {
      limited("acquire-handover", acquireTimeout = 3.seconds).use { db =>
        Deferred[IO, Unit].flatMap { taken =>
          holding(db, 1).use(_ => taken.complete(()) *> IO.sleep(300.millis)).start.flatMap { holder =>
            taken.get *> db.inSession(run(one)).timed.flatTap(_ => holder.joinWithNever).map { case (took, answer) =>
              answer shouldBe 1
              took should be >= 200.millis
              took should be < 3.seconds
            }
          }
        }
      }
    }

    "keep no connection for requests that gave up waiting" in {
      val name = "acquire-gave-up"
      limited(name, max = 2, acquireTimeout = 50.millis).use { db =>
        for {
          gaveUp   <- holding(db, 2).surround(List.fill(50)(db.inSession(run(one)).attempt).parSequence)
          bothFree <- holding(db, 2).surround(IO.unit).timeout(2.seconds).attempt
          stillTwo <- holding(db, 2).surround(db.inSession(run(one)).attempt)
        } yield {
          gaveUp.forall(unavailable) shouldBe true
          bothFree.isRight shouldBe true
          unavailable(stillTwo) shouldBe true
        }
      }
    }

    "lose no connection when timeouts race with connections being returned" in {
      limited("acquire-race", max = 3, acquireTimeout = 4.millis).use { db =>
        for {
          random <- Random.scalaUtilRandom[IO]
          _      <- List.range(0, 400).parTraverseN(40)(_ => random.betweenInt(0, 8).flatMap(ms => db.inSession(IO.sleep(ms.millis)).attempt))
          _      <- IO.sleep(200.millis)
          all    <- holding(db, 3).surround(IO.unit).timeout(2.seconds).attempt
        } yield all.isRight shouldBe true
      }
    }

    "stop the wait of a request that went away and leave the connection to others" in {
      limited("acquire-cancelled", acquireTimeout = 30.seconds).use { db =>
        holding(db, 1).use(_ => db.inSession(run(one)).start.flatMap(waiter => IO.sleep(100.millis) *> waiter.cancel.timed)).flatMap {
          case (cancelling, _) =>
            db.inSession(run(one)).timeout(2.seconds).map { answer =>
              cancelling should be < 1.second
              answer shouldBe 1
            }
        }
      }
    }

    "release a connection that arrives just as its request is cancelled or times out" in {
      List(30.seconds, 1.micro, Duration.Inf)
        .traverse_ { acquireTimeout =>
          (Ref[IO].of(0), Ref[IO].of(0)).flatMapN { (acquired, released) =>
            val round = Deferred[IO, Unit].flatMap { handover =>
              val connection = Resource.make(handover.get *> acquired.update(_ + 1))(_ => released.update(_ + 1))
              PgTransactor.bounded(connection, acquireTimeout, Tracer.noop[IO]).use_.attempt.start.flatMap { request =>
                IO.cede *> (handover.complete(()), request.cancel).parTupled.void
              }
            }
            round.replicateA_(5000) *> IO.sleep(200.millis) *> (acquired.get, released.get).tupled.map { case (opened, closed) =>
              withClue(s"acquire timeout $acquireTimeout: ")(closed shouldBe opened)
            }
          }
        }
        .as(succeed)
    }
  }

  "A lost connection" should {
    "be reported as unavailable when nothing listens on the database port" in {
      val closedPort = IO.blocking {
        val socket = new ServerSocket(0);
        try socket.getLocalPort
        finally socket.close()
      }
      closedPort.flatMap { port =>
        transactorWith(_.copy(port = port, max = 1)).use(_.inSession(run(one)).attempt).map(result => unavailable(result) shouldBe true)
      }
    }

    "be replaced before the next request when the server ended it while idle" in {
      limited("terminated-idle").use { db =>
        for {
          before     <- db.inSession(run(backendPid))
          terminated <- withSession(summon[Session[IO]].unique(terminate)(before))
          _          <- IO.sleep(200.millis)
          next       <- db.inSession(run(backendPid))
        } yield {
          terminated shouldBe true
          next should not be before
        }
      }
    }

    "be reported as unavailable when the server ends the backend mid-query, after which the pool connects again" in {
      limited("terminated-busy").use { db =>
        for {
          started <- Deferred[IO, Int]
          busy    <- db.inSession(run(backendPid).flatMap(started.complete) *> run(sleepTenSeconds)).attempt.start
          pid     <- started.get
          _       <- IO.sleep(200.millis)
          _       <- withSession(summon[Session[IO]].unique(terminate)(pid))
          lost    <- busy.joinWithNever.timeout(5.seconds)
          after   <- db.inSession(run(backendPid))
        } yield {
          unavailable(lost) shouldBe true
          after should not be pid
        }
      }
    }
  }
}
