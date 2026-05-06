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

## HEAD/GET consistency, `If-Range`, and resumption

In `RESUME_IF_VALID` mode the downloader threads the HEAD's `ETag` (or
`Last-Modified` when no ETag is present) into the per-chunk GET as `If-Range`.
A 206 means the validator still matches and the partial download is sound; a
200 on a ranged GET-with-If-Range means the server has replaced the resource —
the adapter surfaces this as `GetResponse.ifRangeMismatch()` and the downloader
fails fast with `RESOURCE_CHANGED` rather than corrupting the partial.

In `FRESH` mode (default) `If-Range` is not sent. A 200 response on a ranged
GET in this mode is the legacy "server lies about Accept-Ranges" path: only
chunk 0 has run, no parallel work has started, and the body is treated as the
full file (see "The 200 OK vs 206 Partial Content corruption hazard").

The two semantics for a 200 on a ranged GET are distinguished entirely by
whether `If-Range` was sent — the adapter knows this and reports it on the
`GetResponse`, so the Downloader does not have to track mode separately.

## Resumption state machine

The sidecar `<dest>.part.json` records `version`, `url`, `etag`,
`lastModified`, `contentLength`, `chunkSize`, and a hex-encoded `BitSet` of
completed chunk indices. It is written atomically (`.part.json.tmp` →
`fsync` → rename) after each chunk's successful write+verify.

```
                       ┌──────────────┐
                       │  download()  │
                       └──────┬───────┘
                              ▼
                         ┌────────┐
                         │  HEAD  │
                         └───┬────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
        FRESH (default)             RESUME_IF_VALID
              │                             │
              │              ┌──────────────┴──────────────┐
              │              ▼                             ▼
              │    .part.json missing            .part.json present
              │              │                             │
              │              │            ┌────────────────┴───────────────┐
              │              │            ▼                                ▼
              │              │  validators match HEAD?           any field differs
              │              │  (url, etag/lastModified,         (or unreadable)
              │              │   contentLength, chunkSize)                 │
              │              │            │                                ▼
              │              │            │                    fail RESOURCE_CHANGED
              │              │            │                    (preserve .part / .part.json
              │              │            │                     so caller can decide)
              │              │            │
              ▼              ▼            ▼
       delete any old   write fresh   reuse existing
       .part / .part.json,  manifest    manifest;
       create fresh       (empty       skip chunks
       empty .part        bitmap)      already in bitmap
              │              │            │
              └──────────┬───┴────────────┘
                         ▼
              ┌──────────────────────────┐
              │ probe chunk 0            │
              │ (with If-Range in        │
              │  RESUME_IF_VALID mode;   │
              │  skipped if already in   │
              │  the completed bitmap)   │
              └────────┬─────────────────┘
                       │
        ┌──────────────┼──────────────────────────┐
        ▼              ▼                          ▼
     200,           200, no             206 (Content-Range
     If-Range       If-Range            valid)
     sent           sent                          │
        │              │                          ▼
        ▼          treat as          parallel chunks 1..N-1
   fail RESOURCE   full body         (skip those already in
   _CHANGED       and commit         bitmap; per-chunk:
                  (single             update bitmap +
                  chunk path)         atomic manifest flush)
                                              │
                                              ▼
                                ┌───────────────────────────┐
                                │ verifyAndCommit:          │
                                │   stream digest (if any)  │
                                │   asm.commit() {          │
                                │     fsync, atomic move,   │
                                │     delete manifest       │
                                │   }                       │
                                └───────────────────────────┘
```

`FRESH` always wipes existing artifacts. `RESUME_IF_VALID` either replays the
bitmap or fails fast with `RESOURCE_CHANGED`. There is no path that silently
mixes old and new bytes — every transition is either ticked off in the
bitmap, fenced by `If-Range`, or terminated.

Single-stream downloads (`!canParallel`) bypass the manifest entirely: there
is no checkpointable state, so resumption is a no-op even when requested.

## Progress dispatch and the single-thread invariant

`DownloaderOptions.progressListener(...)` is observed via a small dispatcher
that owns one virtual thread. Producers (the main thread before chunks fan
out, and the per-chunk virtual threads after each successful write) `emit()`
events into a `LinkedBlockingQueue`; the dispatcher thread loops `take()` →
`listener.onProgress(event)`.

The invariant — exactly one thread invokes the listener — means listener
implementations do not need any synchronisation, and that mutable state inside
the listener (e.g. a running `bytesCompleted` counter, a list of per-chunk
durations) is safe without locks. The CLI's `JsonAccumulator` and
`ConsoleProgressListener` both rely on this.

A listener that throws is caught at the dispatcher; the first such exception
is logged to `System.err` once with the event class name, and the download
proceeds. Subsequent listener throws are dropped silently — silence at the end
of the run is preferable to noisy logs that obscure the actual download
result.

When the listener is `ProgressListener.NO_OP` (the default), the dispatcher
short-circuits — no thread, no queue — so users who don't care about progress
pay nothing.

## Streaming integrity verification

When `DownloaderOptions.expectedDigest(...)` is configured, the downloader
streams the temp file through a `MessageDigest` after the last chunk completes
and **before** `FileAssembler.commit()`. The read uses a 64 KiB buffer in a
single pass — no double-buffering — so the cost scales linearly with file size
and does not require holding the body in memory.

The check runs *before* the atomic move on purpose: if the computed digest does
not match, `IntegrityException` is thrown, doDownload's catch path runs
`asm.abort()` (deletes the `.part` file), and the destination path is never
touched. Callers see `DownloadError.INTEGRITY_FAILURE`. The contract is the
same as for any other failure mode: a corrupted file is never visible at the
destination path, even momentarily.

Whether or not an expected digest is supplied, `DownloadResult.Success` exposes
`Optional<byte[]> sha256()` — populated when verification ran, empty otherwise.
The `Algorithm` enum is single-member (`SHA_256`) to keep the surface
extensible without speculative members; new algorithms add an enum constant
and a `digestLengthBytes()` arm.

## Atomic write strategy and failure cleanup guarantees

`FileAssembler` opens the temp file at the deterministic path `<dest>.part`
in the same directory as the destination (so `Files.move` stays on the same
filesystem and `ATOMIC_MOVE` is available). The lifecycle:

1. `open` — `FileChannel.open(<dest>.part, WRITE | READ | CREATE)`. In FRESH mode,
   any existing `.part` and `.part.json` are deleted first; in RESUME mode they
   are preserved so the existing partial can be extended.
2. `write` — concurrent `FileChannel.write(buf, position)` calls at non-overlapping
   offsets (thread-safe per JDK spec)
3. `commit` — `FileChannel.force(true)` (fsync data + metadata), `Files.move(temp,
   dest, ATOMIC_MOVE, REPLACE_EXISTING)`, then `Files.deleteIfExists(manifestFile)`
4. `abort` — channel close. In FRESH mode also deletes `.part` and `.part.json`;
   in RESUME mode preserves them so the caller can retry `--resume`.

**Success**: destination exists and is complete; `.part` and `.part.json` are gone.
**Failure in FRESH mode**: `.part` deleted; destination never written.
**Failure in RESUME mode**: `.part` and `.part.json` preserved; destination never written.
**Existing destination on failure**: because the temp file is moved (not the destination
overwritten), and `ATOMIC_MOVE` never ran, the original destination file is left intact.
**Destination is a directory**: detected in the `FileAssembler` constructor before
any temp file is created; fails with `IO_ERROR`.

`abort()` and `close()` are both idempotent.

## Retry policy

Retryable triggers: HTTP `408`, `429`, `500`, `502`, `503`, `504`; `IOException`; connect/read timeout.  
Not retried: all other `4xx` codes (auth failure, not-found, etc.). `Content-Range` validation and truncated-body checks run outside the per-chunk retry loop in `Downloader`, so a protocol violation fails the entire download immediately rather than triggering a retry. Future refactors should preserve this invariant by keeping those checks outside `downloadChunk`.

Delay formula: `random(0, min(30 s, baseDelay × 2^attempt))` — full jitter prevents thundering-herd. Server-provided `Retry-After` on `429`/`503` overrides the formula.

## Chaos testing

The downloader's correctness claims — "any failure leaves no artifact at the
destination, and any success delivers the source bytes verbatim" — are
straightforward to prove for an individual error path with a hand-written test.
What's harder is proving they hold under arbitrary mixtures of those errors
across concurrent chunks. A single hand-written test for "503 on chunk 0,
truncated body on chunk 2, mid-body socket reset on chunk 5, malformed
Content-Range on chunk 6" is fragile, expensive to write, and doesn't
generalize.

The chaos suite (`@Tag("chaos")`, opt-in via `-PchaosTests`) replaces that
exhaustive-by-hand exercise with a property test seeded by a deterministic
RNG. `ChaosHttpAdapter` exposes 14 fault classes and picks one per GET from a
weighted distribution; `ChaosPropertyTest` runs the downloader against it
under 120 seeds and asserts:

> **Invariant.** The download ends in exactly one of two states.
>
> 1. `DownloadResult.Success` — the destination file's SHA-256 matches the
>    source corpus; nothing else.
> 2. `DownloadResult.Failure` with a typed `DownloadError`, the destination
>    does not exist, and no `.part` / `.part.json` / `.part.json.tmp` files
>    linger on disk.

A success with corrupted bytes, a failure with a half-written destination, or
a failure that lingers `.part` artifacts in fresh mode are all assertion
failures with a printable seed. Replaying with the same seed reproduces the
exact fault sequence.

### Fault classes

- HTTP `408`, `429`, `500`, `502`, `503`, `504` (status-only — chunk gets
  retried; if retries exhaust, the typed `HTTP_ERROR` failure path runs)
- HTTP `200` on a ranged GET (the corruption hazard the probe-chunk defends
  against in FRESH mode; `ifRangeMismatch` triggers `RESOURCE_CHANGED` in
  RESUME mode)
- `206` with truncated body (caught by the per-chunk byte-count check)
- `206` with malformed `Content-Range` header
- `206` with `Content-Range` mismatching the requested range
- `IOException` mid-body (simulated socket reset; retried)
- Slowloris (1 ms-per-byte, capped to 16 bytes — long enough to exercise the
  trickle path, short enough to keep the suite under a second)
- Per-chunk delay jitter (sleep 0–50 ms before responding)
- Pass-through (no fault — the success branch must dominate frequently
  enough that a property run isn't all failures)

### Why in-process

Chaos tests are deliberately in-process (`HttpAdapter` injection, no
Testcontainers) for two reasons:

- **Speed.** 120 seeded runs over a 64 KiB corpus complete in under a
  second on a developer machine. A Docker round-trip per seed would push
  the suite into minutes, and slow chaos suites stop being run.
- **Determinism.** A real HTTP server's faults aren't reproducible with a
  seed; an in-process adapter's are. When a regression slips in, the seed
  in the assertion message is enough to bisect.

### Past offenders

When this harness catches a regression, record the seed and the commit it
caught in `ChaosHttpAdapter`'s class Javadoc. That paper-trail is more
valuable than any single test method: it shows the harness was used, not
just built.

## Resource ownership and cleanup

`Downloader` owns the `HttpClient` created inside `JdkHttpAdapter` and closes it in `Downloader.close()`. If an externally provided `HttpAdapter` is injected (test or custom), and it implements `AutoCloseable`, it is also closed via the pattern-match in `close()`. `ExecutorService` pools in `downloadAsync` and `downloadChunksParallel` are both used in try-with-resources (Java 21 `AutoCloseable` support).

## What is deliberately out of scope

- **Bandwidth throttling**: reduce `parallelism` and `chunkSize`.
- **HTTP/2 multiplexing**: `HttpClient` negotiates HTTP/2 automatically when the server supports it.
- **Multi-source / torrent-style**: each download targets one URI.
- **GUI**, **authentication**, **metrics framework**.
- **Resume across `chunkSize` changes**: the manifest invalidates; fail-fast wins over reconciliation.
