package madrileno.utils.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

class StorageConfigSpec extends AnyWordSpec with Matchers {

  private val applicationConf = ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.noSystem())

  private def load(overrides: String = ""): StorageConfig =
    ConfigSource.fromConfig(ConfigFactory.parseString(overrides).withFallback(applicationConf.getConfig("storage"))).loadOrThrow[StorageConfig]

  "StorageConfig" should {
    "use s3 when OBJECT_STORAGE is not set, so a deployment that sets only the S3 variables keeps working" in {
      load().backend shouldBe ObjectStorageBackend.S3
    }

    "keep disk objects under uploads when STORAGE_DISK_ROOT is not set" in {
      load().disk.root shouldBe "uploads"
    }

    "read backend = disk and its root" in {
      val config = load("backend = disk\ndisk.root = \"/tmp/objects\"")
      config.backend shouldBe ObjectStorageBackend.Disk
      config.disk.root shouldBe "/tmp/objects"
    }

    "reject a backend it does not know" in {
      an[Exception] should be thrownBy load("backend = ftp")
    }
  }

  "ObjectStoreRuntime.fromConfig" should {
    "build the disk store for backend = disk" in {
      ObjectStoreRuntime.fromConfig(load("backend = disk")).use(r => IO(r.objectStore shouldBe a[DiskObjectStore])).unsafeRunSync()
    }

    "build the S3 store for backend = s3" in {
      ObjectStoreRuntime.fromConfig(load()).use(r => IO(r.objectStore shouldBe a[S3ObjectStore])).unsafeRunSync()
    }
  }
}
