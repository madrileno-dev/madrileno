package madrileno.utils.storage

import cats.effect.{IO, Resource}
import fs2.io.file.Path as FsPath
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.net.URI

trait ObjectStoreRuntime {
  def objectStore: ObjectStore
}

object ObjectStoreRuntime {

  def disk(root: FsPath, maxFetchBytes: Long): ObjectStoreRuntime = new ObjectStoreRuntime {
    override val objectStore: ObjectStore = new DiskObjectStore(root, maxFetchBytes)
  }

  def fromConfig(config: StorageConfig): Resource[IO, ObjectStoreRuntime] = config.backend match {
    case ObjectStorageBackend.Disk => Resource.pure(disk(FsPath(config.disk.root), config.maxFetchBytes))
    case ObjectStorageBackend.S3   => s3(config)
  }

  def s3(config: StorageConfig): Resource[IO, ObjectStoreRuntime] = {
    val s3Config = config.objectStorage
    for {
      client <- Resource.fromAutoCloseable(IO {
                  S3AsyncClient
                    .builder()
                    .endpointOverride(new URI(s3Config.endpoint))
                    .region(Region.of(s3Config.region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)))
                    .forcePathStyle(true)
                    .build()
                })
      presigner <-
        Resource.fromAutoCloseable(IO {
          S3Presigner
            .builder()
            .endpointOverride(new URI(s3Config.endpoint))
            .region(Region.of(s3Config.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        })
    } yield new ObjectStoreRuntime {
      override val objectStore: ObjectStore = new S3ObjectStore(client, presigner, s3Config.bucket, config.maxFetchBytes)
    }
  }
}
