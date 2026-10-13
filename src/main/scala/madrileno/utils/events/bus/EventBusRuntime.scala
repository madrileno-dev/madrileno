package madrileno.utils.events.bus

import cats.effect.kernel.Deferred
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.parser
import madrileno.utils.async.Memoize
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventCodec
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import skunk.data.Identifier

import scala.concurrent.duration.*

trait EventBusRuntime {
  def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E]
}

object EventBusRuntime {

  def local: EventBusRuntime = new EventBusRuntime {
    override def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E] = new LocalEventBus[E](maxQueued)
  }

  def postgres(transactor: Transactor)(using Supervisor[IO], TelemetryContext): EventBusRuntime = new EventBusRuntime {
    override def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E] = new PostgresEventBus[E](transactor, name, maxQueued)
  }

  private class LocalEventBus[E](maxQueued: Int) extends EventBus[E] {
    private val topic: IO[Topic[IO, E]] = Memoize(Topic[IO, E])

    override def publish(event: E): IO[Unit]                 = topic.flatMap(_.publish1(event)).void
    override def subscribe: Stream[IO, E]                    = Stream.eval(topic).flatMap(_.subscribe(maxQueued))
    override def subscribeAwait: Resource[IO, Stream[IO, E]] = Resource.eval(topic).flatMap(_.subscribeAwait(maxQueued))
  }

  private class PostgresEventBus[E: EventCodec](
    transactor: Transactor,
    name: String,
    maxQueued: Int
  )(using
    supervisor: Supervisor[IO],
    tc: TelemetryContext)
      extends EventBus[E]
      with LoggingSupport {

    private val identifier =
      Identifier.fromValue(name).fold(msg => throw new IllegalArgumentException(s"Invalid channel name '$name': $msg"), identity)
    private val codec = EventCodec[E]

    private val listenerReady: Ref[IO, Deferred[IO, Unit]] =
      Ref.unsafe(Deferred.unsafe[IO, Unit])

    private val topic: IO[Topic[IO, E]] = Memoize {
      Topic[IO, E].flatTap(t => supervisor.supervise(listenLoop(t).foreverM))
    }

    override def publish(event: E): IO[Unit] =
      transactor.notify(identifier, codec.encode(event).noSpaces)

    override def subscribe: Stream[IO, E] =
      Stream.eval(topic).flatMap(_.subscribe(maxQueued))

    override def subscribeAwait: Resource[IO, Stream[IO, E]] =
      for {
        t <- Resource.eval(topic)
        _ <- Resource.eval(listenerReady.get.flatMap(_.get).timeout(30.seconds))
        s <- t.subscribeAwait(maxQueued)
      } yield s

    private def listenLoop(sink: Topic[IO, E]): IO[Unit] =
      transactor
        .listen(identifier, maxQueued)
        .use { stream =>
          listenerReady.get.flatMap(_.complete(()).attempt.void) *>
            stream
              .evalMap { notification =>
                parser.parse(notification.value).flatMap(codec.decode) match {
                  case Right(event) => sink.publish1(event).void
                  case Left(err)    => logger.warn(err)(s"Failed to decode notification on $name: ${notification.value}")
                }
              }
              .compile
              .drain
        }
        .handleErrorWith { t =>
          resetListenerReadyIfCompleted *>
            logger.warn(t)(s"LISTEN on $name dropped — reconnecting after 1s") *>
            IO.sleep(1.second)
        }

    private def resetListenerReadyIfCompleted: IO[Unit] =
      listenerReady.get.flatMap(_.tryGet).flatMap {
        case Some(_) => Deferred[IO, Unit].flatMap(listenerReady.set)
        case None    => IO.unit
      }
  }
}
