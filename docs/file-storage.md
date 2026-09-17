# File storage

The codebase ships an object-storage abstraction with two backends — local disk for the no-deps demo, and any S3-compatible service (AWS S3, Silo/MinIO, R2, etc.) for production. Wine-auction images are the worked example: see `madrileno.auction` for an end-to-end module that uses it.

## What you get

```
src/main/scala/madrileno/utils/storage/
  ObjectStore.scala        # the trait + StorageKey (validated) + GetResult ADT
  DiskObjectStore.scala    # local filesystem impl
  S3ObjectStore.scala      # AWS SDK v2 impl + S3Config
  ObjectStoreRuntime.scala # disk(...) / s3(...) factories (s3 is Resource-shaped)
  SignedUrlTtl.scala       # opaque FiniteDuration; per-module knob
  StorageConfig.scala      # pureconfig wrapper for S3Config
```

The contract is `put(key, metadata, body) -> bytes written`, `get(key, ttl, fileName) -> Streamed | Redirected | NotFound`, `delete(key)`. `put` returns the actual byte count, so callers don't have to count themselves.

## Quick start (dev)

`docker compose up -d` brings up [Silo](https://silo.pgsty.com/) (a maintained MinIO fork; upstream's Docker Hub images were pulled) on `127.0.0.1:59000` (S3 API) and `127.0.0.1:59001` (console — login `minioadmin` / `minioadmin`). A `silo-init` sidecar runs `mc mb --ignore-existing local/madrileno` once the server is healthy, so the bucket is there on first boot.

`.env.sample` is wired against that container:

```
S3_ENDPOINT=http://localhost:59000
S3_REGION=us-east-1
S3_BUCKET=madrileno
S3_ACCESS_KEY_ID=minioadmin
S3_SECRET_ACCESS_KEY=minioadmin
```

`Main.scala` always wires the S3 backend:

```scala
storageConfig      <- Resource.eval(IO.delay(config.at("storage").loadOrThrow[StorageConfig]))
objectStoreRuntime <- ObjectStoreRuntime.s3(storageConfig)
```

If you want the disk backend instead (e.g. zero-deps local dev or running tests outside Testcontainers), swap that line for `Resource.pure(ObjectStoreRuntime.disk(FsPath("./uploads"), storageConfig.maxFetchBytes))`. There's intentionally no config-driven dispatch — the choice lives in `Main`, the same way `CacheRuntime.scaffeine` and `RateLimiterRuntime.scaffeine()` do.

## The abstraction

```scala
trait ObjectStore {
  def put(key: StorageKey, contentType: `Content-Type`, body: Stream[IO, Byte]): IO[Long]
  def get(key: StorageKey, ttl: SignedUrlTtl, fileName: Option[String]): IO[ObjectStore.GetResult]
  def delete(key: StorageKey): IO[Unit]
  def presignPut(key: StorageKey, ttl: SignedUrlTtl, contentType: `Content-Type`, contentLength: Long): IO[PresignedPut]
  def head(key: StorageKey): IO[Option[ObjectStat]]
  def fetchBytes(key: StorageKey): IO[Option[ByteVector]]
}

object ObjectStore {
  enum GetResult {
    case Streamed(contentType: `Content-Type`, fileName: Option[String], body: Stream[IO, Byte])
    case Redirected(url: Uri)
    case NotFound
  }
}

final case class ObjectStat(sizeBytes: Long, contentType: `Content-Type`)
final case class PresignedPut(url: Uri, signedHeaders: Headers)
```

A few decisions worth flagging:

- **`get` returns an ADT, not `Response[IO]`.** Each backend picks the access mode that fits. `S3ObjectStore` returns `Redirected` (a presigned URL — the client downloads from S3 directly, the API never proxies bytes). `DiskObjectStore` returns `Streamed` (we have the bytes locally; cheaper than redirecting to ourselves). The router maps either result to an http4s response.
- **`fileName` is per-fetch, not stored on the object.** S3 bakes it into the presign via `responseContentDisposition`; disk passes it through to the router which sets the header. That's why the storage layer doesn't persist filenames — the *caller* knows what to name the download (e.g. the auction's `auction_image.file_name` column).
- **`put` returns the actual byte count.** Disk streams via `Files[IO].writeAll` and reads `Files.size` after the write. S3 buffers via `body.compile.to(ByteVector)` because `S3.putObject` requires a known `Content-Length`; the buffer is bounded by `http.max-request-size` (10 MiB default). For larger streamed uploads, swap to `S3TransferManager` (multipart) — out of scope for the template.
- **`presignPut` is S3-only.** Returns a `PresignedPut(url, signedHeaders)` bound to the given content-type and size — the client (browser, mobile) uploads directly to the bucket. The signed headers are the headers AWS bound into the SigV4 signature; clients MUST echo them back on the PUT or the bucket will reject with `SignatureDoesNotMatch`. Returning typed `Headers` instead of just a `Uri` avoids breakage when SDK options (SSE, checksums) introduce new signed headers in the future. Disk raises `UnsupportedOperationException` because direct uploads only make sense against an HTTP-addressable bucket; in dev with the disk backend, fall back to multipart-through-the-API.
- **`head` for commit-after-direct-upload verification.** After a client PUTs to a presigned URL, the server calls `head` to confirm the object actually landed (and learns its size + content-type) before persisting the row. Returns `None` for missing keys.
- **`fetchBytes` for analyzers and variant generation.** Pulls the object into memory; returns `None` for missing keys. Bounded by `storage.max-fetch-bytes` (10 MiB default) — S3 uses a ranged GET (`bytes=0-N`) and disk takes from the read stream up to N+1 bytes. If the response (or stream) exceeds the cap, `ObjectTooLarge` is raised; the heap never materializes more than `maxFetchBytes + 1` bytes regardless of the actual object size. The cap is hard, not a TOCTOU pre-check, so it survives a client overwriting the object via `presignPut` between the size check and the read.
- **`StorageKey` is validated at the opaque-type boundary.** `StorageKey.apply` rejects empty / absolute / `..`-segment / NUL-containing keys, so the disk backend can't be coerced into writing outside `root` regardless of the caller. `StorageKey.render` is the domain-aware accessor for code that needs the raw string.

## Wiring it into a module

`ApplicationLoader` exposes a single `objectStore: ObjectStore` for the whole app. Modules just declare it as an abstract `val` and use it.

`AuctionModule.scala`:

```scala
trait AuctionModule extends RouteProvider with AuthRouteProvider /* ... */ {
  val objectStore: ObjectStore
  protected lazy val signedUrlTtl: SignedUrlTtl = SignedUrlTtl(5.minutes)

  private val auctionImageService = wire[AuctionImageService]
  // ...
}
```

`signedUrlTtl` is per-module on purpose. Different file types want different presign lifetimes — auction images can be longer-lived than, say, one-time download links — and macwire picks the right `SignedUrlTtl` by type without ambiguity.

## Storing a file

Two flows are wired in `AuctionImageService`. Multipart-through-the-API works against any backend (including the disk backend in dev). Direct upload uses a presigned PUT URL so client bytes never touch the API server — production-friendly with S3.

### Multipart upload

The pattern is **upload first, persist second, clean up storage on persist failure.** Anything else either leaves a row pointing at a missing object (worse: `404` for a row that exists) or leaves an orphan blob with no row.

`AuctionImageService.attachImage` (simplified):

```scala
for {
  id         <- IdGenerator.generateId(AuctionImageId)
  now        <- Clock[IO].realTimeInstant
  key         = StorageKey(s"auctions/$auctionId/images/$id")
  actualSize <- objectStore.put(key, contentType, content)
  result     <- persistAttached(...).attempt.flatMap {
                  case Right(AttachImageResult.Attached(image)) => IO.pure(AttachImageResult.Attached(image))
                  case Right(other)                             => objectStore.delete(key).attempt.as(other)
                  case Left(t)                                  => objectStore.delete(key).attempt *> IO.raiseError(t)
                }
} yield result
```

The persist runs inside `transactor.inTransaction` and locks the auction row via `auctionRepository.findForUpdate(auctionId)` — this serializes concurrent uploads to the same auction so `nextPosition` doesn't race. It also enqueues the analyzer task (see below) atomically with the row insert.

`actualSize` is what we actually wrote (`put` returns it). The DB row uses that, not any client-provided `Content-Length` header — multipart parts frequently lie about size or omit the header entirely.

### Direct upload (presign + commit)

S3-only. The client makes two calls to the API plus one PUT to S3:

1. **`POST /auctions/{auctionId}/images/presign`** — server validates ownership, generates `imageId` + storage key, returns `PresignedUploadDto(imageId, url, signedHeaders)`.
2. **Client `PUT`s** the bytes to the presigned URL, echoing every header in `signedHeaders` (the bucket rejects with `SignatureDoesNotMatch` if any signed header is missing or differs).
3. **`POST /auctions/{auctionId}/images/commit`** with `{imageId, fileName}` — server `head`s the storage key to verify the object landed (and learn `sizeBytes` + `contentType` directly from S3), inserts the row, and enqueues the analyzer task. **Idempotent**: a retry that lands after a successful first commit returns `Committed(existingRow)` instead of failing on the PK.

No upload-then-cleanup pattern here — the bucket is authoritative; if the client never finishes step 2, no row is ever created and the orphan blob is reaped by bucket-level lifecycle rules (set up out-of-band).

### Analyzer and variant generation

Persisting a row enqueues `analyzeImageTask` (a one-time scheduler task) in the same transaction. The analyzer:

1. `fetchBytes` from storage (`storage.max-fetch-bytes` cap applies — see the storage utils).
2. `Imaging.info` to extract format/dimensions and whether the upload carries EXIF.
3. If it carries EXIF, `Imaging.convert` it — decoding applies the EXIF Orientation, so the re-encode bakes the rotation into the pixels and drops all camera/GPS metadata — and write those canonical bytes back. Re-runs `Imaging.info` so `markAnalyzed` records the upright dimensions. EXIF-free uploads are left byte-for-byte.
4. `markAnalyzed(id, sizeBytes, width, height, format, now)` and enqueues one `generateVariantTask` per `VariantSpec` — all in one transaction, so a scheduler outage between mark + enqueue rolls back and the retry replays cleanly.

Each variant task renders its spec (`VariantSpec.Thumb` → `Imaging.cover(256, 256)`, `VariantSpec.Medium` → `Imaging.resize(1024)`), `put`s the bytes, and inserts the variant row via `INSERT … ON CONFLICT (auction_image_id, spec) DO NOTHING` so concurrent retries can't violate the unique constraint. On insert failure the task best-effort-deletes the rendered blob and re-raises so the scheduler retries.

## Serving a file

`AuctionImageService.serveImage` returns `Option[ObjectStore.GetResult]` — `None` when the row is missing or the image belongs to a different auction; otherwise the storage backend's result, which the router pattern-matches:

```scala
auctionImageService.serveImage(auctionId, imageId).map {
  case None | Some(ObjectStore.GetResult.NotFound) => error(NotFound, "image-not-found", "Image not found")
  case Some(ObjectStore.GetResult.Redirected(url)) => Response[IO](Status.SeeOther, headers = Headers(Location(url)))
  case Some(ObjectStore.GetResult.Streamed(ct, fileName, body)) =>
    val baseHeaders = Headers(ct)
    val headers     = fileName.fold(baseHeaders)(name =>
      baseHeaders.put(`Content-Disposition`("attachment", Map(ci"filename" -> name)))
    )
    Response[IO](Status.Ok, headers = headers, body = body)
}
```

For S3, `S3ObjectStore.get` issues a `HeadObject` before presigning so a missing key surfaces as `GetResult.NotFound` (instead of redirecting to a presigned URL that S3 will reject with 404). It costs one extra round-trip; in exchange the API contract holds for both backends.

## Position invariants and reordering

The `auction_image` table has a partial unique index on `(auction_id, position) WHERE deleted_at IS NULL`. That keeps positions strict at the DB level — concurrent attaches that race on `nextPosition` get one winner per position; the loser fails on the constraint and the upload-first/persist-second pattern cleans up its blob.

Reordering can't update positions row-by-row — swapping `[A=0, B=1]` to `[A=1, B=0]` would hit the constraint mid-transaction. `AuctionImageRepository.bulkSetPositions` does a two-phase update inside a single transaction:

1. Move every targeted row to a unique negative slot (`-(idx + 1)` per loop index) — the partial unique allows it because no other row uses negatives.
2. Move every targeted row to its final position.

The `ImagePosition` opaque type still rejects negatives in the domain — the negatives only exist as raw integers inside the repo helper, never as domain values.

## Production deployment

- **Pre-create buckets out-of-band.** `ObjectStoreRuntime.s3` does not auto-create. Use Terraform/CDK in cloud, `mc mb` in dev (already wired into `silo-init`), or whatever your provider offers.
- **IAM on AWS.** The app needs `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on the bucket prefix. Presigned URLs inherit the signer's permissions; the credentials in `.env` need `GetObject` for downloads to work after the redirect.
- **Region constraints.** AWS S3 requires `LocationConstraint` for any region other than `us-east-1`. Bucket creation isn't done by the app, so this isn't a runtime concern — but it's something to remember in your IaC.
- **Presigned URL TTL.** Set per module via `SignedUrlTtl`. Short TTLs are good for security; long TTLs are CDN-cache-friendly. Five minutes is the auction-images default; raise or lower for your file type.
- **`max-request-size` is the upload ceiling.** `application.conf` sets `max-request-size = 10 MiB` so multipart uploads aren't rejected by the global `EntityLimiter`. If you need larger files, bump that — or split into chunked / resumable uploads, which is out of scope for this template.

## Testing

- **`TestObjectStoreRuntime.inMemory`** — a `Ref`-backed in-memory `ObjectStore`. Used by `TestApplicationLoader` so route specs don't need Silo. `get` returns `Streamed` (no presigned URL needed). `presignPut` returns a fake `PresignedPut` (URL like `https://example.test/<key>`) so service-level tests can exercise the presign/commit/analyzer/variant pipeline without a real bucket.
- **`S3ObjectStoreSpec`** — runs against a Silo Testcontainer pinned to `RELEASE.2026-09-16T00-00-00Z`. Creates the test bucket explicitly (the app no longer auto-creates) using a `Resource.fromAutoCloseable` S3 client so nothing leaks.
- **`AuctionImageServiceSpec`** — exercises the service against real Postgres + the in-memory store. Covers multipart attach, detach, reorder, serve cross-auction guards, presign + commit (happy + missing-object + wrong-owner + idempotent retry), the analyzer task (happy + already-analyzed no-op + missing row), and variant generation (`Thumb` 256×256, `Medium` 1024-on-long-edge, idempotent re-runs).
- **`AuctionImageRouterSpec`** — full baklava DSL against `TestApplicationLoader`. Uses `pl.iterators.baklava.{Multipart, FilePart}` for the upload body so the endpoints land in `target/baklava/openapi/openapi.yml` (and the oRPC / simple-HTML outputs).

## Things that catch people out

- **The disk backend writes meta first, data second.** The sidecar `.meta` file holds the rendered `Content-Type`. `get` only returns `Streamed` if both files exist. Failure cases:
  - Crash before meta lands → no files, `get` returns `NotFound`.
  - Crash between meta and data → orphan `.meta`, no data file, `get` returns `NotFound` (the `dataExists` check catches this); a subsequent `put` for the same key overwrites the orphan meta.
  - Crash mid-data-write → orphan `.meta` + truncated data file, `get` returns `Streamed` with corrupt bytes. Production deployments should run a sweeper or rely on filesystem-level integrity; this is the demo-grade tradeoff.
- **S3 uploads buffer.** `S3.putObject` requires a known `Content-Length`, so the S3 backend compiles the body to a `ByteVector` before the PUT. That works fine for the demo's image-sized uploads bounded by `max-request-size = 10 MiB`. If you need streamed uploads at larger sizes, replace the body construction with `S3TransferManager.uploadPart` / multipart-upload — it's a meaningful chunk of code and out of scope for this template.
- **`StorageKey` is just a string.** No leading slash, no scheme, no bucket prefix — just an object key relative to the configured bucket / disk root. Build it from your domain (e.g. `s"auctions/$auctionId/images/$id"`).
- **The redirect goes to S3, not back to the API.** Browsers download directly from S3 using the presigned URL. If your CORS / network setup blocks that, you'll see a working `303` followed by a failed download — the API never sees the second request.
