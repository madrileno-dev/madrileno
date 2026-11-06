package madrileno.support

import cats.effect.{IO, Ref}
import fs2.{Chunk, Stream}
import madrileno.utils.storage.{ObjectStat, ObjectStore, ObjectStoreRuntime, ObjectTooLarge, PresignedPut, SignedUrlTtl, StorageKey}
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, Headers, Uri}
import org.typelevel.ci.CIString
import scodec.bits.ByteVector

object TestObjectStoreRuntime {

  def inMemory: ObjectStoreRuntime = inMemory(maxFetchBytes = Long.MaxValue)

  def inMemory(maxFetchBytes: Long): ObjectStoreRuntime = {
    val state = Ref.unsafe[IO, Map[StorageKey, (`Content-Type`, ByteVector)]](Map.empty)

    new ObjectStoreRuntime {
      override val objectStore: ObjectStore = new ObjectStore {
        override def put(
          key: StorageKey,
          contentType: `Content-Type`,
          body: Stream[IO, Byte]
        ): IO[Long] =
          body.compile.to(ByteVector).flatMap(bytes => state.update(_.updated(key, contentType -> bytes)).as(bytes.size.toLong))

        override def get(
          key: StorageKey,
          ttl: SignedUrlTtl,
          fileName: Option[String]
        ): IO[ObjectStore.GetResult] = {
          val _ = ttl
          state.get.map(_.get(key)).map {
            case Some((ct, bytes)) => ObjectStore.GetResult.Streamed(ct, fileName, Stream.chunk(Chunk.byteVector(bytes)))
            case None              => ObjectStore.GetResult.NotFound
          }
        }

        override def delete(key: StorageKey): IO[Unit] = state.update(_ - key)

        override def presignPut(
          key: StorageKey,
          ttl: SignedUrlTtl,
          contentType: `Content-Type`,
          contentLength: Long
        ): IO[PresignedPut] = {
          val _       = ttl
          val url     = Uri.unsafeFromString(s"https://example.test/${key.render}")
          val headers = Headers(
            Header.Raw(CIString("content-type"), Header[`Content-Type`].value(contentType)),
            Header.Raw(CIString("content-length"), contentLength.toString)
          )
          IO.pure(PresignedPut(url, headers))
        }

        override def head(key: StorageKey): IO[Option[ObjectStat]] =
          state.get.map(_.get(key).map { case (ct, bytes) => ObjectStat(bytes.size, ct) })

        override def fetchBytes(key: StorageKey): IO[Option[ByteVector]] =
          state.get.flatMap(_.get(key) match {
            case None                                           => IO.pure(None)
            case Some((_, bytes)) if bytes.size > maxFetchBytes =>
              IO.raiseError(ObjectTooLarge(key, bytes.size, maxFetchBytes))
            case Some((_, bytes)) => IO.pure(Some(bytes))
          })
      }
    }
  }
}
