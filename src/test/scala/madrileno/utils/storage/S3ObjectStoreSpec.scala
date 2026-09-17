package madrileno.utils.storage

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import madrileno.support.TestData
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait
import scodec.bits.ByteVector
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import sttp.client4.basicRequest
import sttp.client4.httpurlconnection.HttpURLConnectionBackend
import sttp.model.Uri as SttpUri

import java.net.URI
import scala.concurrent.duration.DurationInt

class S3ObjectStoreSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestContainerForAll {

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "pgsty/silo:RELEASE.2026-09-16T00-00-00Z",
    exposedPorts = Seq(9000),
    env = Map("MINIO_ROOT_USER" -> "minioadmin", "MINIO_ROOT_PASSWORD" -> "minioadmin"),
    command = Seq("server", "/data"),
    waitStrategy = Wait.forHttp("/minio/health/live").forPort(9000)
  )

  private def configFor(container: GenericContainer): StorageConfig = StorageConfig(
    maxFetchBytes = 10L * 1024L * 1024L,
    objectStorage = S3Config(
      endpoint = s"http://${container.host}:${container.mappedPort(9000)}",
      region = "us-east-1",
      bucket = s"test-${TestData.randomUuid()}",
      accessKeyId = "minioadmin",
      secretAccessKey = "minioadmin"
    )
  )

  private def createBucket(config: StorageConfig): IO[Unit] = {
    val s3Config       = config.objectStorage
    val clientResource = Resource.fromAutoCloseable(IO {
      S3AsyncClient
        .builder()
        .endpointOverride(new URI(s3Config.endpoint))
        .region(Region.of(s3Config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)))
        .forcePathStyle(true)
        .build()
    })
    clientResource.use { client =>
      IO.fromCompletableFuture(IO(client.createBucket(CreateBucketRequest.builder().bucket(s3Config.bucket).build()))).void
    }
  }

  private val plainText = `Content-Type`(MediaType.text.plain)
  private val ttl       = SignedUrlTtl(5.minutes)

  "S3ObjectStore against Silo" should {
    "round-trip put/get/delete" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/object.txt")
            val bytes = "hello s3".getBytes("UTF-8")
            for {
              written     <- store.put(key, plainText, Stream.emits(bytes))
              afterPut    <- store.get(key, ttl, Some("hello.txt"))
              _           <- store.delete(key)
              afterDelete <- store.get(key, ttl, Some("hello.txt"))
            } yield {
              written shouldBe bytes.length.toLong
              afterPut shouldBe a[ObjectStore.GetResult.Redirected]
              afterDelete shouldBe ObjectStore.GetResult.NotFound
            }
          }
          .timeout(60.seconds)
    }

    "head returns size + content-type for an existing object and None after delete" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/head.txt")
            val bytes = "head test".getBytes("UTF-8")
            for {
              _             <- store.put(key, plainText, Stream.emits(bytes))
              statBeforeDel <- store.head(key)
              _             <- store.delete(key)
              statAfterDel  <- store.head(key)
            } yield {
              statBeforeDel shouldBe Some(ObjectStat(bytes.length.toLong, plainText))
              statAfterDel shouldBe None
            }
          }
          .timeout(60.seconds)
    }

    "fetchBytes returns the stored bytes and None for a missing key" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store   = runtime.objectStore
            val key     = StorageKey("test/fetch.bin")
            val missing = StorageKey("test/never-was.bin")
            val bytes   = (0 to 255).map(_.toByte).toArray
            for {
              _        <- store.put(key, plainText, Stream.emits(bytes))
              fetched  <- store.fetchBytes(key)
              notFound <- store.fetchBytes(missing)
            } yield {
              fetched shouldBe Some(ByteVector(bytes))
              notFound shouldBe None
            }
          }
          .timeout(60.seconds)
    }

    "fetchBytes returns Some(empty) for a zero-byte object" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/empty.bin")
            for {
              _       <- store.put(key, plainText, Stream.empty)
              fetched <- store.fetchBytes(key)
            } yield fetched shouldBe Some(ByteVector.empty)
          }
          .timeout(60.seconds)
    }

    "fetchBytes raises ObjectTooLarge above the configured cap" in withContainers { container =>
      val config = configFor(container).copy(maxFetchBytes = 16L)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/too-big.bin")
            val bytes = Array.fill[Byte](32)(0)
            store.put(key, plainText, Stream.emits(bytes)) *>
              store.fetchBytes(key).attempt.map(_.left.toOption).map { err =>
                err shouldBe defined
                err.get shouldBe a[ObjectTooLarge]
              }
          }
          .timeout(60.seconds)
    }

    "presignPut produces a URL + signed headers that accept a matching PUT" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/upload.txt")
            val bytes = "uploaded directly".getBytes("UTF-8")
            for {
              presigned <- store.presignPut(key, ttl, plainText, bytes.length.toLong)
              sttpHeaders = presigned.signedHeaders.headers.map(h => sttp.model.Header(h.name.toString, h.value))
              response <- IO.blocking {
                            val backend = HttpURLConnectionBackend()
                            try {
                              basicRequest
                                .put(SttpUri(new URI(presigned.url.renderString)).queryValueSegmentsEncoding(SttpUri.QuerySegmentEncoding.All))
                                .body(bytes)
                                .headers(sttpHeaders*)
                                .send(backend)
                            } finally backend.close()
                          }
              stat <- store.head(key)
            } yield {
              presigned.signedHeaders.get(org.typelevel.ci.CIString("content-type")).map(_.head.value) shouldBe Some("text/plain")
              response.code.code shouldBe 200
              stat shouldBe Some(ObjectStat(bytes.length.toLong, plainText))
            }
          }
          .timeout(60.seconds)
    }
  }
}
