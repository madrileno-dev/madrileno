package madrileno.utils.storage

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

enum ObjectStorageBackend {
  case Disk, S3
}

object ObjectStorageBackend {
  given ConfigReader[ObjectStorageBackend] = ConfigReader.fromString {
    case "disk" => Right(Disk)
    case "s3"   => Right(S3)
    case other  => Left(CannotConvert(other, "ObjectStorageBackend", "expected disk or s3"))
  }
}

final case class DiskStorageConfig(root: String) derives ConfigReader

final case class StorageConfig(
  maxFetchBytes: Long,
  backend: ObjectStorageBackend,
  disk: DiskStorageConfig,
  objectStorage: S3Config)
    derives ConfigReader {
  require(maxFetchBytes >= 0, s"storage.max-fetch-bytes must be >= 0, got $maxFetchBytes")

  def summary: String = backend match {
    case ObjectStorageBackend.Disk => s"disk (${disk.root})"
    case ObjectStorageBackend.S3   => s"s3 (bucket ${objectStorage.bucket} at ${objectStorage.endpoint})"
  }
}
