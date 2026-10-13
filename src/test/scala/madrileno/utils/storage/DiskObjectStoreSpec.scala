package madrileno.utils.storage

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class DiskObjectStoreSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val plainText = `Content-Type`(MediaType.text.plain)
  private val ttl       = SignedUrlTtl(5.minutes)

  private def withStore[A](maxFetchBytes: Long = Long.MaxValue)(f: (ObjectStore, FsPath) => IO[A]): IO[A] =
    Files[IO].tempDirectory.use(root => f(ObjectStoreRuntime.disk(root, maxFetchBytes).objectStore, root))

  private def drain(result: ObjectStore.GetResult): IO[Option[(`Content-Type`, Option[String], ByteVector)]] = result match {
    case ObjectStore.GetResult.Streamed(ct, name, body) => body.compile.to(ByteVector).map(bytes => Some((ct, name, bytes)))
    case _                                              => IO.pure(None)
  }

  "DiskObjectStore" should {
    "stream a stored object back with its content type and the requested file name" in withStore() { (store, _) =>
      val key   = StorageKey("docs/hello.txt")
      val bytes = "hello disk".getBytes("UTF-8")
      for {
        written <- store.put(key, plainText, Stream.emits(bytes))
        got     <- store.get(key, ttl, Some("download.txt")).flatMap(drain)
      } yield {
        written shouldBe bytes.length.toLong
        got shouldBe Some((plainText, Some("download.txt"), ByteVector(bytes)))
      }
    }

    "create nested directories for keys with path segments" in withStore() { (store, root) =>
      val key = StorageKey("a/b/c/object.bin")
      for {
        _      <- store.put(key, plainText, Stream.emits(Array[Byte](1, 2, 3)))
        exists <- Files[IO].exists(root / "a" / "b" / "c" / "object.bin")
        meta   <- Files[IO].exists(root / "a" / "b" / "c" / "object.bin.meta")
      } yield {
        exists shouldBe true
        meta shouldBe true
      }
    }

    "answer NotFound for a missing key" in withStore() { (store, _) =>
      store.get(StorageKey("missing"), ttl, None).map(_ shouldBe ObjectStore.GetResult.NotFound)
    }

    "answer NotFound when the data file exists but its metadata is missing or unreadable" in withStore() { (store, root) =>
      for {
        _          <- Stream.emits("orphan".getBytes("UTF-8")).through(Files[IO].writeAll(root / "orphan")).compile.drain
        noMeta     <- store.get(StorageKey("orphan"), ttl, None)
        noMetaStat <- store.head(StorageKey("orphan"))
        _          <- Stream.emits("x".getBytes("UTF-8")).through(Files[IO].writeAll(root / "broken")).compile.drain
        _          <- Stream.emits("not a content type".getBytes("UTF-8")).through(Files[IO].writeAll(root / "broken.meta")).compile.drain
        badMeta    <- store.get(StorageKey("broken"), ttl, None)
      } yield {
        noMeta shouldBe ObjectStore.GetResult.NotFound
        noMetaStat shouldBe None
        badMeta shouldBe ObjectStore.GetResult.NotFound
      }
    }

    "report size and content type from head and None after delete" in withStore() { (store, root) =>
      val key   = StorageKey("stat.txt")
      val bytes = "sized".getBytes("UTF-8")
      for {
        _        <- store.put(key, plainText, Stream.emits(bytes))
        before   <- store.head(key)
        _        <- store.delete(key)
        after    <- store.head(key)
        dataGone <- Files[IO].exists(root / "stat.txt")
        metaGone <- Files[IO].exists(root / "stat.txt.meta")
      } yield {
        before shouldBe Some(ObjectStat(bytes.length.toLong, plainText))
        after shouldBe None
        dataGone shouldBe false
        metaGone shouldBe false
      }
    }

    "tolerate deleting a key that was never stored" in withStore() { (store, _) =>
      store.delete(StorageKey("never")).map(_ => succeed)
    }

    "overwrite an existing object in place" in withStore() { (store, _) =>
      val key = StorageKey("rewrite.txt")
      for {
        _     <- store.put(key, plainText, Stream.emits("first version".getBytes("UTF-8")))
        _     <- store.put(key, `Content-Type`(MediaType.application.json), Stream.emits("{}".getBytes("UTF-8")))
        stat  <- store.head(key)
        bytes <- store.fetchBytes(key)
      } yield {
        stat shouldBe Some(ObjectStat(2L, `Content-Type`(MediaType.application.json)))
        bytes shouldBe Some(ByteVector("{}".getBytes("UTF-8")))
      }
    }

    "fetchBytes returns the bytes, an empty vector for an empty object and None for a missing key" in withStore() { (store, _) =>
      val key   = StorageKey("fetch.bin")
      val empty = StorageKey("empty.bin")
      val bytes = (0 to 255).map(_.toByte).toArray
      for {
        _       <- store.put(key, plainText, Stream.emits(bytes))
        _       <- store.put(empty, plainText, Stream.empty)
        fetched <- store.fetchBytes(key)
        nothing <- store.fetchBytes(empty)
        missing <- store.fetchBytes(StorageKey("absent.bin"))
      } yield {
        fetched shouldBe Some(ByteVector(bytes))
        nothing shouldBe Some(ByteVector.empty)
        missing shouldBe None
      }
    }

    "fetchBytes raises ObjectTooLarge above the configured cap and allows exactly the cap" in withStore(maxFetchBytes = 16L) { (store, _) =>
      val big   = StorageKey("big.bin")
      val exact = StorageKey("exact.bin")
      for {
        _       <- store.put(big, plainText, Stream.emits(Array.fill[Byte](17)(0)))
        _       <- store.put(exact, plainText, Stream.emits(Array.fill[Byte](16)(0)))
        tooBig  <- store.fetchBytes(big).attempt
        allowed <- store.fetchBytes(exact)
      } yield {
        tooBig.left.toOption.collect { case ObjectTooLarge(key, size, max) => (key, size, max) } shouldBe Some((big, 17L, 16L))
        allowed.map(_.size) shouldBe Some(16L)
      }
    }

    "refuse presigned uploads" in withStore() { (store, _) =>
      store.presignPut(StorageKey("up.bin"), ttl, plainText, 10L).attempt.map {
        _.left.toOption.map(_.getClass) shouldBe Some(classOf[UnsupportedOperationException])
      }
    }
  }

  "ObjectStoreRuntime.disk" should {
    "share one store per runtime" in {
      Resource.pure[IO, Unit](()).use { _ =>
        Files[IO].tempDirectory.use { root =>
          val runtime = ObjectStoreRuntime.disk(root, Long.MaxValue)
          IO(runtime.objectStore should be theSameInstanceAs runtime.objectStore)
        }
      }
    }
  }
}
