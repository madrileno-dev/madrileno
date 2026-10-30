package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.opentelemetry.sdk.trace.data.SpanData
import madrileno.support.{RecordingTracer, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SchedulerTracingSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val config = SchedulerConfig(pollingInterval = 100.millis, heartbeatInterval = 100.millis)

  private def awaitSpan(
    recorder: RecordingTracer,
    name: String,
    remaining: FiniteDuration = 5.seconds
  ): IO[SpanData] =
    recorder.spansNamed(name).flatMap {
      case span :: _                         => IO.pure(span)
      case Nil if remaining <= Duration.Zero => IO.raiseError(new RuntimeException(s"Timed out waiting for span $name"))
      case Nil                               => IO.sleep(50.millis) *> awaitSpan(recorder, name, remaining - 50.millis)
    }

  private def queryText(span: SpanData): String = RecordingTracer.stringAttribute(span, "db.query.text").getOrElse("")

  private def heartbeatsIn(spans: List[SpanData]): List[SpanData] =
    spans.filter(s => s.getName == "UPDATE scheduled_task" && queryText(s).contains("last_heartbeat = $1"))

  private def awaitHeartbeats(
    recorder: RecordingTracer,
    count: Int,
    remaining: FiniteDuration = 5.seconds
  ): IO[Unit] =
    recorder.spans.map(heartbeatsIn).flatMap {
      case beats if beats.sizeIs >= count  => IO.unit
      case _ if remaining <= Duration.Zero => IO.raiseError(new RuntimeException(s"Timed out waiting for $count heartbeats"))
      case _                               => IO.sleep(50.millis) *> awaitHeartbeats(recorder, count, remaining - 50.millis)
    }

  "Scheduler tracing" should {
    "leave idle polls out of traces while still recording the sweep" in {
      for {
        recorder <- RecordingTracer.create
        spans    <- tracedTransactor(recorder.tracerProvider).use { tx =>
                   Scheduler(tx, config)(using Clock[IO], recorder.context).run().use { _ =>
                     IO.sleep(600.millis) *> recorder.spans
                   }
                 }
      } yield {
        spans.map(_.getName) should contain("scheduler.sweep")
        spans.map(_.getName) should contain("UPDATE scheduled_task")
        spans.map(_.getName) should not contain "scheduler.pick"
      }
    }

    "name the execution span after the task and nest its heartbeats under it" in {
      val descriptor = TaskDescriptor[Unit]("test-heartbeat")
      for {
        recorder <- RecordingTracer.create
        task = Task.oneTime(descriptor)(_ => awaitHeartbeats(recorder, 2))
        result <- tracedTransactor(recorder.tracerProvider).use { tx =>
                    val scheduler = Scheduler(tx, config)(using Clock[IO], recorder.context)
                    scheduler.run(oneTimeTasks = List(task)).use { _ =>
                      for {
                        _       <- scheduler.client.schedule(task.instance("beat-1", ()))
                        execute <- awaitSpan(recorder, "scheduler.execute test-heartbeat")
                        spans   <- recorder.spans
                      } yield (execute, spans)
                    }
                  }
        (execute, spans) = result
        heartbeats       = heartbeatsIn(spans)
      } yield {
        execute.getParentSpanContext.isValid shouldBe false
        RecordingTracer.stringAttribute(execute, "task.instance") shouldBe Some("beat-1")
        heartbeats.size should be >= 2
        heartbeats.map(_.getParentSpanId).distinct shouldBe List(execute.getSpanId)
      }
    }

    "link the execution span to the span that scheduled the task" in {
      val descriptor = TaskDescriptor[Unit]("test-link")
      val task       = Task.oneTime(descriptor)(_ => IO.unit)
      for {
        recorder <- RecordingTracer.create
        result   <- tracedTransactor(recorder.tracerProvider).use { tx =>
                    val scheduler = Scheduler(tx, config)(using Clock[IO], recorder.context)
                    scheduler.run(oneTimeTasks = List(task)).use { _ =>
                      for {
                        _       <- recorder.context.tracer.span("request").use(_ => scheduler.client.schedule(task.instance("link-1", ())))
                        request <- awaitSpan(recorder, "request")
                        execute <- awaitSpan(recorder, "scheduler.execute test-link")
                      } yield (request, execute)
                    }
                  }
        (request, execute) = result
        links              = execute.getLinks.asScala.toList
      } yield {
        execute.getParentSpanContext.isValid shouldBe false
        links.map(_.getSpanContext.getTraceId) shouldBe List(request.getTraceId)
        links.map(_.getSpanContext.getSpanId) shouldBe List(request.getSpanId)
      }
    }
  }
}
