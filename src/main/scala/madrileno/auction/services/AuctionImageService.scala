package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.{Codec, Decoder, Encoder}
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.user.domain.UserId
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.imaging.{Height, ImageFormat, Imaging, Width}
import madrileno.utils.json.JsonProtocol.given
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.storage.{ObjectStat, ObjectStore, PresignedPut, SignedUrlTtl, StorageKey}
import madrileno.utils.task.{OneTimeTask, SchedulerClient, Task, TaskDescriptor}
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import pl.iterators.sealedmonad.syntax.*
import scodec.bits.ByteVector

import java.time.Instant

class AuctionImageService(
  auctionRepository: AuctionRepository,
  auctionImageRepository: AuctionImageRepository,
  objectStore: ObjectStore,
  transactor: Transactor,
  schedulerClient: SchedulerClient,
  signedUrlTtl: SignedUrlTtl
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {

  val analyzeImageTask: OneTimeTask[AuctionImageId] =
    Task.oneTime(TaskDescriptor[AuctionImageId]("analyze-auction-image"))(task => analyze(task.payload))

  val generateVariantTask: OneTimeTask[GenerateVariantPayload] =
    Task.oneTime(TaskDescriptor[GenerateVariantPayload]("generate-auction-image-variant"))(task =>
      generateVariant(task.payload.imageId, task.payload.spec)
    )

  private def analyze(imageId: AuctionImageId): IO[Unit] = (for {
    image <- transactor
               .inSession(auctionImageRepository.find(imageId))
               .valueOrF[Unit](logger.warn(s"analyze: image $imageId not found, skipping"))
               .ensure(_.analyzedAt.isEmpty, ())
    bytes <- objectStore
               .fetchBytes(image.storageKey)
               .valueOrF[Unit](logger.warn(s"analyze: bytes missing for $imageId at ${image.storageKey.render}, skipping"))
    info <- Imaging
              .info(bytes)
              .valueOrF[Unit](logger.warn(s"analyze: $imageId at ${image.storageKey.render} is not a recognized image, skipping"))
    // has EXIF ⇒ re-encode: bakes in the orientation scrimage applied on decode, strips camera/GPS metadata
    canonical <- (if (info.hasExif) Imaging.convert(bytes, info.format) else IO.pure(bytes)).seal
    _         <- (if (info.hasExif)
            objectStore.put(image.storageKey, contentTypeFor(info.format), Stream.chunk(Chunk.byteVector(canonical))).void
          else IO.unit).seal
    canonicalInfo <- Imaging
                       .info(canonical)
                       .valueOrF[Unit](logger.warn(s"analyze: canonical bytes for $imageId no longer recognizable, skipping"))
    now <- Clock[IO].realTimeInstant.seal
    _   <- transactor.inTransaction {
           for {
             _ <- auctionImageRepository
                    .markAnalyzed(
                      imageId,
                      SizeBytes(canonical.size),
                      canonicalInfo.dimensions.width,
                      canonicalInfo.dimensions.height,
                      canonicalInfo.format,
                      now
                    )
             _ <- VariantSpec.All.traverse_(spec => schedulerClient.scheduleTransactionally(variantTaskInstance(imageId, spec)))
           } yield ()
         }.seal
  } yield ()).run

  private def generateVariant(imageId: AuctionImageId, spec: VariantSpec): IO[Unit] = (for {
    image <- transactor
               .inSession(auctionImageRepository.find(imageId))
               .valueOrF[Unit](logger.warn(s"generateVariant: image $imageId not found, skipping"))
    bytes <- objectStore
               .fetchBytes(image.storageKey)
               .valueOrF[Unit](logger.warn(s"generateVariant: bytes missing for $imageId at ${image.storageKey.render}, skipping"))
    rendered <- renderVariant(spec, bytes).seal
    info     <- Imaging
              .info(rendered)
              .valueOrF[Unit](logger.warn(s"generateVariant: rendered output for ($imageId, $spec) is not recognizable"))
    variantId <- IdGenerator.generateId(AuctionImageVariantId).seal
    variantKey = StorageKey(s"auctions/${image.auctionId}/images/$imageId/variants/$spec")
    _   <- objectStore.put(variantKey, contentTypeFor(spec.format), Stream.chunk(Chunk.byteVector(rendered))).void.seal
    now <- Clock[IO].realTimeInstant.seal
    variant = AuctionImageVariant(
                id = variantId,
                auctionImageId = imageId,
                spec = spec,
                storageKey = variantKey,
                width = info.dimensions.width,
                height = info.dimensions.height,
                format = spec.format,
                generatedAt = now
              )
    _ <- transactor
           .inSession(auctionImageRepository.saveVariant(variant))
           .handleErrorWith(t =>
             objectStore.delete(variantKey).attempt *>
               logger.warn(t)(s"generateVariant: insert failed for ($imageId, $spec); cleaned up blob") *>
               IO.raiseError(t)
           )
           .seal
  } yield ()).run

  private def renderVariant(spec: VariantSpec, bytes: ByteVector): IO[ByteVector] = spec match {
    case VariantSpec.Thumb  => Imaging.cover(bytes, Width(256), Height(256), spec.format)
    case VariantSpec.Medium => Imaging.resize(bytes, maxDimension = 1024, spec.format)
  }

  private def variantTaskInstance(imageId: AuctionImageId, spec: VariantSpec): Task[GenerateVariantPayload] =
    generateVariantTask.instance(s"variant-$imageId-$spec", GenerateVariantPayload(imageId, spec))

  private def contentTypeFor(format: ImageFormat): `Content-Type` = format match {
    case ImageFormat.Jpeg => `Content-Type`(MediaType.image.jpeg)
    case ImageFormat.Png  => `Content-Type`(MediaType.image.png)
    case ImageFormat.Gif  => `Content-Type`(MediaType.image.gif)
  }

  def listImages(auctionId: AuctionId): IO[List[AuctionImage]] =
    transactor.inSession(auctionImageRepository.listByAuction(auctionId))

  def listImagesWithVariants(auctionId: AuctionId): IO[List[(AuctionImage, List[AuctionImageVariant])]] =
    transactor.inSession {
      for {
        images   <- auctionImageRepository.listByAuction(auctionId)
        variants <- auctionImageRepository.listVariantsByImages(images.map(_.id))
      } yield images.map(image => image -> variants.getOrElse(image.id, Nil))
    }

  def serveVariant(
    auctionId: AuctionId,
    imageId: AuctionImageId,
    spec: VariantSpec
  ): IO[Option[ObjectStore.GetResult]] = (for {
    _ <- transactor
           .inSession(auctionImageRepository.find(imageId))
           .valueOr[Option[ObjectStore.GetResult]](None)
           .ensure(_.auctionId == auctionId, None)
    variant <- transactor
                 .inSession(auctionImageRepository.findVariant(imageId, spec))
                 .valueOr[Option[ObjectStore.GetResult]](None)
    result <- objectStore.get(variant.storageKey, signedUrlTtl, fileName = None).seal
  } yield result match {
    case ObjectStore.GetResult.NotFound => None
    case other                          => Some(other)
  }).run

  def attachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    fileName: String,
    contentType: `Content-Type`,
    content: Stream[IO, Byte]
  ): IO[AttachImageResult] = {
    transactor.inSession(auctionRepository.find(auctionId)).flatMap {
      case None                                          => IO.pure(AttachImageResult.AuctionNotFound)
      case Some(auction) if auction.sellerId != sellerId => IO.pure(AttachImageResult.NotOwner)
      case Some(_)                                       =>
        for {
          id  <- IdGenerator.generateId(AuctionImageId)
          now <- Clock[IO].realTimeInstant
          key = StorageKey(s"auctions/$auctionId/images/$id")
          actualSize <- objectStore.put(key, contentType, content)
          result     <- persistAttached(auctionId, sellerId, id, key, fileName, contentType, actualSize, now).attempt.flatMap {
                      case Right(AttachImageResult.Attached(image)) => IO.pure(AttachImageResult.Attached(image))
                      case Right(other)                             => cleanupAfterFailedPersist(key) *> IO.pure(other)
                      case Left(t) => cleanupAfterFailedPersist(key) *> logger.error(t)(s"Persist failed for ${key.render}") *> IO.raiseError(t)
                    }
        } yield result
    }
  }

  private def persistAttached(
    auctionId: AuctionId,
    sellerId: UserId,
    id: AuctionImageId,
    key: StorageKey,
    fileName: String,
    contentType: `Content-Type`,
    actualSize: Long,
    now: Instant
  ): IO[AttachImageResult] = {
    transactor.inTransaction {
      (for {
        _ <- auctionRepository
               .findForUpdate(auctionId)
               .valueOr[AttachImageResult](AttachImageResult.AuctionNotFound)
               .ensure(_.sellerId == sellerId, AttachImageResult.NotOwner)
        pos <- auctionImageRepository.nextPosition(auctionId).seal
        image = AuctionImage.newlyAttached(
                  id = id,
                  auctionId = auctionId,
                  storageKey = key,
                  fileName = fileName,
                  contentType = contentType,
                  sizeBytes = SizeBytes(actualSize),
                  position = ImagePosition(pos),
                  uploadedAt = now
                )
        _ <- auctionImageRepository.save(image).seal
        _ <- schedulerClient.scheduleTransactionally(analyzeImageTask.instance(s"analyze-$id", id)).seal
      } yield AttachImageResult.Attached(image)).run
    }
  }

  private def cleanupAfterFailedPersist(key: StorageKey): IO[Unit] =
    objectStore.delete(key).attempt.flatMap {
      case Right(_) => IO.unit
      case Left(t)  => logger.warn(t)(s"Storage cleanup failed for ${key.render}; possible orphan blob")
    }

  def detachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId
  ): IO[DetachImageResult] = {
    type Outcome = Either[DetachImageResult, AuctionImage]
    transactor
      .inTransaction {
        (for {
          _ <- auctionRepository
                 .findForUpdate(auctionId)
                 .valueOr[Outcome](Left(DetachImageResult.NotFound))
                 .ensure(_.sellerId == sellerId, Left(DetachImageResult.NotOwner))
          image <- auctionImageRepository
                     .find(imageId)
                     .valueOr[Outcome](Left(DetachImageResult.NotFound))
                     .ensure(_.auctionId == auctionId, Left(DetachImageResult.NotFound))
          now <- Clock[IO].realTimeInstant.seal
          _   <- auctionImageRepository.softDelete(imageId, now).seal
        } yield Right(image): Outcome).run
      }
      .flatMap {
        case Right(image) =>
          objectStore
            .delete(image.storageKey)
            .attempt
            .flatMap {
              case Left(t)  => logger.warn(t)(s"Storage delete failed for ${image.storageKey.render}; row already soft-deleted")
              case Right(_) => IO.unit
            }
            .as(DetachImageResult.Detached)
        case Left(outcome) => IO.pure(outcome)
      }
  }

  def reorderImages(
    auctionId: AuctionId,
    sellerId: UserId,
    orderedIds: List[AuctionImageId]
  ): IO[ReorderImagesResult] = {
    transactor.inTransaction {
      (for {
        _ <- auctionRepository
               .findForUpdate(auctionId)
               .valueOr[ReorderImagesResult](ReorderImagesResult.AuctionNotFound)
               .ensure(_.sellerId == sellerId, ReorderImagesResult.NotOwner)
        current <- auctionImageRepository.listByAuctionForUpdate(auctionId).seal
        _       <- IO
               .pure(orderedIds)
               .seal
               .ensure(ids => ids.toSet == current.map(_.id).toSet && ids.length == current.length, ReorderImagesResult.MismatchedIds)
        updates = orderedIds.zipWithIndex.map { case (id, idx) => (id, ImagePosition(idx)) }
        _ <- auctionImageRepository.bulkSetPositions(auctionId, updates).seal
      } yield ReorderImagesResult.Reordered).run
    }
  }

  def presignUpload(
    auctionId: AuctionId,
    sellerId: UserId,
    contentType: `Content-Type`,
    contentLength: Long
  ): IO[PresignUploadResult] = {
    (for {
      _ <- transactor
             .inSession(auctionRepository.find(auctionId))
             .valueOr[PresignUploadResult](PresignUploadResult.AuctionNotFound)
             .ensure(_.sellerId == sellerId, PresignUploadResult.NotOwner)
      imageId <- IdGenerator.generateId(AuctionImageId).seal
      key = StorageKey(s"auctions/$auctionId/images/$imageId")
      presigned <- objectStore.presignPut(key, signedUrlTtl, contentType, contentLength).seal
    } yield PresignUploadResult.Presigned(imageId, presigned)).run
  }

  def commitUpload(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId,
    fileName: String
  ): IO[CommitUploadResult] = {
    val key                                            = StorageKey(s"auctions/$auctionId/images/$imageId")
    val precheck: IO[Either[CommitUploadResult, Unit]] = transactor.inSession {
      for {
        auctionOpt <- auctionRepository.find(auctionId)
        imageOpt   <- auctionImageRepository.findIncludingDeleted(imageId)
      } yield (auctionOpt, imageOpt) match {
        case (None, _)                                                                            => Left(CommitUploadResult.AuctionNotFound)
        case (Some(auction), _) if auction.sellerId != sellerId                                   => Left(CommitUploadResult.NotOwner)
        case (_, Some(existing)) if existing.auctionId == auctionId && existing.deletedAt.isEmpty =>
          Left(CommitUploadResult.Committed(existing))
        case (_, Some(_)) => Left(CommitUploadResult.Conflict)
        case _            => Right(())
      }
    }
    precheck.flatMap {
      case Left(result) => IO.pure(result)
      case Right(_)     =>
        (for {
          stat   <- objectStore.head(key).valueOr[CommitUploadResult](CommitUploadResult.ObjectNotFound)
          now    <- Clock[IO].realTimeInstant.seal
          result <- persistCommittedRow(auctionId, sellerId, imageId, key, fileName, stat, now).seal
        } yield result).run
    }
  }

  private def persistCommittedRow(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId,
    key: StorageKey,
    fileName: String,
    stat: ObjectStat,
    now: Instant
  ): IO[CommitUploadResult] =
    transactor.inTransaction {
      (for {
        _ <- auctionRepository
               .findForUpdate(auctionId)
               .valueOr[CommitUploadResult](CommitUploadResult.AuctionNotFound)
               .ensure(_.sellerId == sellerId, CommitUploadResult.NotOwner)
        _ <- auctionImageRepository
               .findIncludingDeleted(imageId)
               .seal[CommitUploadResult]
               .attempt[Unit, CommitUploadResult] {
                 case Some(existing) if existing.auctionId == auctionId && existing.deletedAt.isEmpty =>
                   Left(CommitUploadResult.Committed(existing))
                 case Some(_) => Left(CommitUploadResult.Conflict)
                 case None    => Right(())
               }
        pos <- auctionImageRepository.nextPosition(auctionId).seal
        image = AuctionImage.newlyAttached(
                  id = imageId,
                  auctionId = auctionId,
                  storageKey = key,
                  fileName = fileName,
                  contentType = stat.contentType,
                  sizeBytes = SizeBytes(stat.sizeBytes),
                  position = ImagePosition(pos),
                  uploadedAt = now
                )
        _ <- auctionImageRepository.save(image).seal
        _ <- schedulerClient.scheduleTransactionally(analyzeImageTask.instance(s"analyze-$imageId", imageId)).seal
      } yield CommitUploadResult.Committed(image)).run
    }

  def serveImage(auctionId: AuctionId, imageId: AuctionImageId): IO[Option[ObjectStore.GetResult]] = (for {
    image <- transactor
               .inSession(auctionImageRepository.find(imageId))
               .valueOr[Option[ObjectStore.GetResult]](None)
               .ensure(_.auctionId == auctionId, None)
    result <- objectStore.get(image.storageKey, signedUrlTtl, Some(image.fileName)).seal
  } yield result match {
    case ObjectStore.GetResult.NotFound => None
    case other                          => Some(other)
  }).run
}

enum AttachImageResult {
  case Attached(image: AuctionImage)
  case AuctionNotFound
  case NotOwner
}

enum DetachImageResult {
  case Detached
  case NotFound
  case NotOwner
}

enum ReorderImagesResult {
  case Reordered
  case AuctionNotFound
  case NotOwner
  case MismatchedIds
}

enum PresignUploadResult {
  case Presigned(imageId: AuctionImageId, presigned: PresignedPut)
  case AuctionNotFound
  case NotOwner
}

enum CommitUploadResult {
  case Committed(image: AuctionImage)
  case AuctionNotFound
  case NotOwner
  case ObjectNotFound
  case Conflict
}

final case class GenerateVariantPayload(imageId: AuctionImageId, spec: VariantSpec) derives Codec.AsObject

object GenerateVariantPayload {
  given Encoder[VariantSpec] = Encoder.encodeString.contramap(_.toString)
  given Decoder[VariantSpec] = Decoder.decodeString.emap(s => VariantSpec.byName(s).toRight(s"Unknown VariantSpec: $s"))
}
