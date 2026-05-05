# Design Notes

## Why Java 21

Java 21 is the current LTS. It provides virtual threads (Project Loom), sealed interfaces, and records — all used here. Zero runtime dependencies; the only non-JDK code is JUnit 5 + AssertJ in test scope.

## Why virtual threads

Virtual threads let each concurrent chunk GET block on I/O without consuming an OS thread. Creating one per in-flight chunk is idiomatic and cheap (≈ 200 bytes of heap per unmounted thread). A `Semaphore(parallelism)` caps actual concurrent GETs at the configured limit while still using a `newVirtualThreadPerTaskExecutor()` for scheduling.

## Why parallel Range GET — and when it is not beneficial

Parallel Range GET saturates the available bandwidth from a CDN that serves objects from multiple edge nodes or that rate-limits per connection. For a single-connection server it adds no throughput and wastes connections. The downloader falls back to single-stream automatically when the server does not advertise `Accept-Ranges: bytes` or `parallelism` is 1.

## Architecture overview

```
Downloader (AutoCloseable)
├── HEAD preflight        → detect server capabilities
├── RangePlanner          → totalBytes + chunkSize → List<ByteRange>
├── FileAssembler         → temp-file lifecycle (open/write/fsync/atomic-move)
│   └── ChunkSink         → positional FileChannel.write (thread-safe per JDK spec)
├── JdkHttpAdapter        → java.net.http.HttpClient (HEAD + ranged GET)
└── RetryPolicy           → attempt + Trigger → Optional<Duration>
```

All types except `Downloader`, `DownloaderOptions`, `DownloadResult`, `DownloadError`, `DownloadHandle`, `HttpAdapter`, and `ByteRange` are package-private implementation details.

## Range planning and inclusive byte boundaries

`Range: bytes=a-b` is inclusive on both ends per RFC 9110. `ByteRange(offset, length)` computes `lastByte = offset + length - 1` and formats as `bytes=offset-lastByte`. `RangePlanner` covers exactly `totalBytes` without overlap or gap, verified by property tests across random sizes and chunk sizes.

## The `200 OK` vs `206 Partial Content` corruption hazard

Some servers advertise `Accept-Ranges: bytes` in HEAD but return `200` with the full body when they receive a ranged GET. If we launched all chunks simultaneously and each received the full body, every chunk would write the complete file starting at its offset — corrupting the output.

**Mitigation**: chunk 0 is always downloaded synchronously as a probe before other chunks start. If the probe returns `200`, we treat the body already in the temp file (written at offset 0) as the complete download, skip all other chunks, and commit. No parallel work has run, so there is no window for corruption.

## `Content-Range` validation

For every `206` response on a ranged GET, the returned `Content-Range` header (if present) is validated against the requested range:

- `start` must equal `range.offset()`
- `end` must equal `range.lastByte()`
- `total` (if not `*`) must equal the `Content-Length` from HEAD

A mismatch means the server returned data for the wrong byte range and the write would corrupt the file. The download fails immediately with `IO_ERROR`; the `.part` file is deleted.

If `Content-Range` is absent on a `206` response, the response is tolerated. RFC 9110 §14.4 does not require the header when the response covers the exact requested range, and some CDNs omit it.

## HEAD/GET consistency and `If-Range`

Without `If-Range`, if the server replaces or truncates the file between the HEAD request and the chunk GETs, the downloader may either fail with `SIZE_MISMATCH` (if the byte count changes) or silently produce a mix of old and new bytes (if the byte count stays the same). This residual risk is accepted.

`JdkHttpAdapter` captures the `ETag` from HEAD (stored in `HeadResponse.etag()`), but this value is not sent on chunk GET requests. Implementing `If-Range` would require adding an `ifRange` parameter to `HttpAdapter.get()`, passing the validator into each ranged GET, and treating a `200` response (resource changed or Range became invalid) as a typed `RESOURCE_CHANGED` failure. That is explicitly future work.

## Atomic write strategy and failure cleanup guarantees

`FileAssembler` creates a temp file in the same directory as the destination (so `Files.move` stays on the same filesystem and `ATOMIC_MOVE` is available). The lifecycle:

1. `open` — `Files.createTempFile(destinationDir, name., .part)`
2. `write` — concurrent `FileChannel.write(buf, position)` calls at non-overlapping offsets (thread-safe per JDK spec)
3. `commit` — `FileChannel.force(true)` (fsync data + metadata), then `Files.move(temp, dest, ATOMIC_MOVE, REPLACE_EXISTING)`
4. `abort` — channel close + `Files.deleteIfExists(temp)`

**Success**: destination exists and is complete.  
**Any failure before commit**: `abort()` is called; `.part` is deleted; destination is never written.  
**Existing destination on failure**: because the temp file is deleted and `ATOMIC_MOVE` never ran, the original destination file is left intact.  
**Destination is a directory**: detected in the `FileAssembler` constructor before any temp file is created; fails with `IO_ERROR`.

`abort()` and `close()` are both idempotent.

## Retry policy

Retryable triggers: HTTP `408`, `429`, `500`, `502`, `503`, `504`; `IOException`; connect/read timeout.  
Not retried: all other `4xx` codes (auth failure, not-found, etc.). `Content-Range` validation and truncated-body checks run outside the per-chunk retry loop in `Downloader`, so a protocol violation fails the entire download immediately rather than triggering a retry. Future refactors should preserve this invariant by keeping those checks outside `downloadChunk`.

Delay formula: `random(0, min(30 s, baseDelay × 2^attempt))` — full jitter prevents thundering-herd. Server-provided `Retry-After` on `429`/`503` overrides the formula.

## Resource ownership and cleanup

`Downloader` owns the `HttpClient` created inside `JdkHttpAdapter` and closes it in `Downloader.close()`. If an externally provided `HttpAdapter` is injected (test or custom), and it implements `AutoCloseable`, it is also closed via the pattern-match in `close()`. `ExecutorService` pools in `downloadAsync` and `downloadChunksParallel` are both used in try-with-resources (Java 21 `AutoCloseable` support).

## What is deliberately out of scope

- **`If-Range` / per-chunk ETag resumption**: adds significant state threading; useful for chunk sizes > 64 MiB where a mid-chunk retry wastes meaningful bandwidth.
- **Caller-supplied SHA-256 verification**: callers can re-read the result path with `MessageDigest`. The property test suite verifies end-to-end byte identity via SHA-256 across 40+ random (size, chunk-size) combinations.
- **Progress callbacks**: would require a callback interface or reactive streams; the `DownloadResult.Success` record reports final byte count and elapsed time.
- **Bandwidth throttling**: reduce `parallelism` and `chunkSize`.
- **HTTP/2 multiplexing**: `HttpClient` negotiates HTTP/2 automatically when the server supports it.
- **Multi-source / torrent-style**: each download targets one URI.
- **CLI**, **GUI**, **resume/checkpoint**, **authentication**, **metrics framework**.

## What I would add with one more day

1. **`If-Range` on each chunk GET**: wire `HeadResponse.etag()` into `JdkHttpAdapter.get()` when a range is requested; handle `200` fallback as a typed `RESOURCE_CHANGED` error.
2. **Optional SHA-256 in `DownloaderOptions`**: stream the committed temp file through `MessageDigest` before the atomic move; one algorithm, 64 KiB buffer, delete `.part` on mismatch.
3. **A thin CLI** (`Main.run(String[])` returning an exit code) with `./gradlew run` support, no framework dependencies.
4. **Bandwidth measurement**: track bytes/second per chunk so the `Success` result can report effective throughput.
