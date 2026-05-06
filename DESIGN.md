# Design Notes

## What I built

A Java 21 library plus thin CLI for parallel `Range`-GET file downloads, with:

- **Concurrent ranged GETs over virtual threads**, bounded by a `Semaphore`
  so the configured `parallelism` is respected without scheduling overhead.
- **A probe chunk at offset 0**, fetched synchronously before any other
  chunks fan out, to detect servers that advertise `Accept-Ranges: bytes` in
  HEAD but return `200 + full body` on a ranged GET — fixing the otherwise
  silent corruption when N parallel writers each receive the entire body.
- **Per-chunk `Content-Range` validation and byte-count truncation checks**
  — protocol-level pre-conditions for commit; failure here always aborts the
  download rather than triggering a retry.
- **Atomic write semantics**: a deterministic `<dest>.part` temp file in the
  destination's directory; `force(true)` then `Files.move(..., ATOMIC_MOVE)`
  on success; never a partial destination on failure.
- **Streaming SHA-256 verification** before the atomic move — corrupt files
  never reach the destination path.
- **Resumable downloads** via a `<dest>.part.json` sidecar manifest
  (atomic flush after each chunk's successful write+verify) plus `If-Range`
  on every ranged GET in resume mode. Any validator drift (URL, ETag,
  Content-Length, chunk size) → fail-fast `RESOURCE_CHANGED`.
- **A `ProgressListener` SPI** with a sealed `ProgressEvent` hierarchy,
  dispatched from a single virtual thread so listeners don't need their own
  synchronisation.
- **Exponential-backoff retry with full jitter** honouring server `Retry-After`
  hints; non-retryable 4xx codes fail immediately.
- **A chaos property test** that injects 14 fault classes per GET under 120
  deterministic seeds and asserts the invariant "Success ⇒ correct bytes;
  Failure ⇒ typed error and zero artifacts."
- **A hand-rolled CLI** with text/JSON reporting, exit codes per failure
  class, and a TTY-aware live progress line.

The non-test runtime is dependency-free; the only artifacts on the
classpath are the JDK and the shaded library itself. JUnit 5 + AssertJ +
Testcontainers are test-scope only.

## Why Java 21 + virtual threads

Virtual threads let each concurrent chunk GET block on I/O without consuming
an OS thread. Creating one per in-flight chunk is idiomatic and cheap
(≈ 200 bytes of heap per unmounted thread). A `Semaphore(parallelism)` caps
the actual concurrent GETs at the configured limit while still using
`newVirtualThreadPerTaskExecutor()` for scheduling — the simplest possible
"bounded parallelism on cheap threads" composition.

## Architecture

```
Downloader (AutoCloseable)
├── HEAD preflight        → server capabilities (Accept-Ranges, ETag, Length)
├── RangePlanner          → totalBytes + chunkSize → List<ByteRange>
├── FileAssembler         → <dest>.part lifecycle (open/write/fsync/atomic-move)
│   └── ChunkSink         → positional FileChannel.write (thread-safe per JDK spec)
├── Manifest              → <dest>.part.json sidecar (RESUME_IF_VALID only)
├── JdkHttpAdapter        → java.net.http.HttpClient (HEAD + ranged GET + If-Range)
├── RetryPolicy           → attempt + Trigger → Optional<Duration> with jitter
└── ProgressDispatcher    → single virtual thread drains a queue of events
```

Public types: `Downloader`, `DownloaderOptions`, `DownloadResult`,
`DownloadError`, `DownloadHandle`, `HttpAdapter`, `HttpStatusException`,
`Algorithm`, `ExpectedDigest`, `ResumeStrategy`, `ProgressListener`,
`ProgressEvent`, `ByteRange`, `cli.Main`. Everything else is package-private.

## Trade-offs

These are calls that could have gone the other way; recording them so a
reviewer can disagree without reverse-engineering the reasoning.

### Probe chunk first vs streaming detection

When a server advertises `Accept-Ranges: bytes` but returns `200 + full
body` on a ranged GET, fanning out N parallel chunks would corrupt the
file (every chunk writes the full body, each at its own offset). The
mitigation is a synchronous probe chunk 0 before any parallel work runs:
if the probe returns `200`, we treat it as the complete download and
commit; no parallel writes ever happened, so corruption is impossible.

The alternative — *streaming detection*, where the first bytes of the
first concurrent chunk are inspected for evidence of a `200` response —
adds protocol-aware byte sniffing into the chunk path and a synchronisation
point to abort siblings. Probe-chunk-first is one extra synchronous round
trip and a clean "no parallel work has run yet" precondition.

### Synchronous chunk 0 over a cooperative discovery protocol

A more elaborate design would issue an OPTIONS or zero-byte GET first to
discover Range support without committing to writing data. The probe-chunk
shape collapses discovery and the first useful byte transfer into one round
trip — at the cost of always making the first chunk synchronous, even on
servers that obviously support Range (e.g. an S3 endpoint). The cost is a
serialisation of the first chunk's latency; the benefit is one fewer round
trip and a single code path that handles `200`-on-ranged-GET correctly.

### JSON sidecar manifest vs binary checkpoint format

The manifest is JSON — flat, human-readable, debuggable with `cat`. A
binary format (e.g. fixed-offset bitmap + length-prefixed strings) would be
faster to read/write and use less disk, but the manifest is rewritten
≤ once per chunk (8 MiB default → ≤ 12.5 KB total writes per GiB
downloaded) and is read only once per resume. Speed isn't the bottleneck;
debuggability is. JSON wins.

### Chaos via property test, not case-by-case units

A unit test per fault class would be easy to write and easy to fail —
"503 on chunk 2 fails the right way" is a one-liner. But the headline
invariant is about *combinations*: a 503 on chunk 0, a truncated body on
chunk 4, a malformed `Content-Range` on chunk 7, all in the same run. 14
fault classes × 8 chunks × per-call sampling ≈ 10⁹ shapes; a property
test seeds the RNG and lets the harness explore. When it finds a bug,
the seed in the failure message replays the exact sequence — strictly
better than a hand-written reduction.

### Per-chunk fsync of manifest vs lazy flush

The manifest is fsynced on every chunk completion. A 1 GiB file at 8 MiB
chunks = 128 fsyncs ≈ 13 ms on an SSD; negligible compared to network
time. Lazy flushing would amortise that but loses the "manifest-on-disk
reflects every committed chunk" property, meaning a crash between batched
flushes silently re-fetches more bytes on resume. Per-chunk durability is
the right correctness/cost trade.

## Range planning and inclusive byte boundaries

`Range: bytes=a-b` is inclusive on both ends per RFC 9110.
`ByteRange(offset, length)` computes `lastByte = offset + length - 1` and
formats as `bytes=offset-lastByte`. `RangePlanner` covers exactly
`totalBytes` without overlap or gap, verified by property tests across
random sizes and chunk sizes.

## Content-Range validation

For every `206` response on a ranged GET the returned `Content-Range`
header (if present) is validated against the requested range:

- `start` must equal `range.offset()`
- `end` must equal `range.lastByte()`
- `total` (if not `*`) must equal the `Content-Length` from HEAD

Mismatch fails immediately with `IO_ERROR`; `.part` is deleted (in FRESH
mode). A missing header is tolerated — RFC 9110 §14.4 does not require it
when the response covers the exact requested range, and some CDNs omit it.

## HEAD/GET consistency, `If-Range`, and resumption

In `RESUME_IF_VALID` mode the downloader threads the HEAD's `ETag` (or
`Last-Modified` when no ETag is present) into the per-chunk GET as
`If-Range`. A `206` means the validator still matches and the partial
download is sound; a `200` on a ranged GET-with-`If-Range` means the
server has replaced the resource — the adapter surfaces this as
`GetResponse.ifRangeMismatch()` and the downloader fails fast with
`RESOURCE_CHANGED`.

In `FRESH` mode `If-Range` is not sent. A `200` response on a ranged GET
in this mode is the legacy "server lies about Accept-Ranges" path: only
chunk 0 has run, no parallel work has started, and the body is treated as
the full file.

The two semantics for a `200` on a ranged GET are distinguished entirely
by whether `If-Range` was sent — the adapter knows this and reports it on
the `GetResponse`, so the Downloader does not have to track mode
separately.

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

`FRESH` always wipes existing artifacts. `RESUME_IF_VALID` either replays
the bitmap or fails fast with `RESOURCE_CHANGED`. There is no path that
silently mixes old and new bytes — every transition is either ticked off
in the bitmap, fenced by `If-Range`, or terminated.

Single-stream downloads (`!canParallel`) bypass the manifest entirely:
there is no checkpointable state, so resumption is a no-op even when
requested.

`ResumeStrategy` carries `@ApiStatus.Experimental` because the two-member
enum is the smallest committed surface — a third value such as
`RESUME_OR_FRESH` (try resume; on validator drift fall back silently
instead of returning `RESOURCE_CHANGED`) is plausible enough that callers
should expect the enum to grow. The `ProgressEvent` records, by contrast,
are spec-fixed by what HTTP downloads produce (offset, length, attempts,
duration), so they are not annotated.

## Streaming integrity verification

When `DownloaderOptions.expectedDigest(...)` is configured, the downloader
streams the temp file through a `MessageDigest` after the last chunk
completes and *before* `FileAssembler.commit()`. The read uses a 64 KiB
buffer in a single pass — no double-buffering — so the cost scales
linearly with file size and does not require holding the body in memory.
On mismatch, `IntegrityException` is thrown, `asm.abort()` runs via the
existing catch path, and the destination path is never touched. Whether
or not an expected digest is supplied, `DownloadResult.Success` exposes
`Optional<byte[]> sha256()` — populated when verification ran, empty
otherwise.

## Atomic write strategy and failure cleanup

`FileAssembler` opens the temp file at the deterministic path
`<dest>.part` in the same directory as the destination (so `Files.move`
stays on the same filesystem and `ATOMIC_MOVE` is available).

- **Open**: `FileChannel.open(<dest>.part, WRITE | READ | CREATE)`. In
  FRESH mode any existing `.part` and `.part.json` are deleted first; in
  RESUME mode they are preserved so the existing partial can be extended.
- **Write**: concurrent `FileChannel.write(buf, position)` at
  non-overlapping offsets (thread-safe per JDK spec).
- **Commit**: `FileChannel.force(true)` (fsync data + metadata),
  `Files.move(temp, dest, ATOMIC_MOVE, REPLACE_EXISTING)`, then
  `Files.deleteIfExists(manifestFile)`.
- **Abort**: channel close. In FRESH mode also deletes `.part` and
  `.part.json`; in RESUME mode preserves them so the caller can retry
  with `--resume`. Idempotent.

## Retry policy

Retryable triggers: HTTP `408`, `429`, `500`, `502`, `503`, `504`;
`IOException`; connect/read timeout. Not retried: all other 4xx codes.
`Content-Range` validation and truncated-body checks run *outside* the
per-chunk retry loop, so a protocol violation fails the entire download
immediately rather than triggering a retry. Future refactors should
preserve this invariant.

Delay formula: `random(0, min(30 s, baseDelay × 2^attempt))` — full
jitter prevents thundering-herd. Server-provided `Retry-After` on
`429`/`503` overrides the formula.

## Progress dispatch and the single-thread invariant

`DownloaderOptions.progressListener(...)` is observed via a small
dispatcher that owns one virtual thread. Producers (the main thread
before chunks fan out, and the per-chunk virtual threads after each
successful write) `emit()` events into a `LinkedBlockingQueue`; the
dispatcher loops `take()` → `listener.onProgress(event)`.

The invariant — exactly one thread invokes the listener — means listener
implementations do not need any synchronisation, and that mutable state
inside the listener (e.g. a running `bytesCompleted` counter, a list of
per-chunk durations) is safe without locks. A throwing listener is caught;
the first exception is logged once to `System.err` with the event class
name; subsequent throws drop silently. `ProgressListener.NO_OP` (the
default) short-circuits the dispatcher entirely.

## Chaos testing

The downloader's correctness claims — "any failure leaves no artifact at
the destination, and any success delivers the source bytes verbatim" —
are straightforward to prove for an individual error path with a hand-written
test. What's harder is proving they hold under arbitrary mixtures of those
errors across concurrent chunks. A single hand-written test for "503 on
chunk 0, truncated body on chunk 2, mid-body socket reset on chunk 5,
malformed Content-Range on chunk 6" is fragile, expensive to write, and
doesn't generalize.

The chaos suite (`@Tag("chaos")`, opt-in via `-PchaosTests`) replaces that
exhaustive-by-hand exercise with a property test seeded by a
deterministic RNG. `ChaosHttpAdapter` exposes 14 fault classes and picks
one per GET from a weighted distribution; `ChaosPropertyTest` runs the
downloader against it under 120 seeds and asserts:

> **Invariant.** The download ends in exactly one of two states.
>
> 1. `DownloadResult.Success` — the destination file's SHA-256 matches
>    the source corpus; nothing else.
> 2. `DownloadResult.Failure` with a typed `DownloadError`, the
>    destination does not exist, and no `.part` / `.part.json` /
>    `.part.json.tmp` files linger on disk.

A success with corrupted bytes, a failure with a half-written
destination, or a failure that lingers `.part` artifacts in fresh mode
are all assertion failures with a printable seed. Replaying with the
same seed reproduces the exact fault sequence.

### Fault classes

- HTTP `408`, `429`, `500`, `502`, `503`, `504` (status-only — the
  downloader retries; on exhaustion the typed `HTTP_ERROR` failure path
  runs)
- HTTP `200` on a ranged GET (the corruption hazard the probe chunk
  defends against in FRESH mode; `ifRangeMismatch` triggers
  `RESOURCE_CHANGED` in RESUME mode)
- `206` with truncated body
- `206` with malformed `Content-Range` header
- `206` with `Content-Range` mismatching the requested range
- `IOException` mid-body (simulated socket reset; retried)
- Slowloris (1 ms-per-byte, capped to 16 bytes)
- Per-chunk delay jitter (sleep 0–50 ms before responding)
- Pass-through (no fault — the success branch must dominate frequently
  enough that a property run isn't all failures)

### Why in-process

Chaos tests are deliberately in-process (`HttpAdapter` injection, no
Testcontainers) for two reasons:

- **Speed.** 120 seeded runs over a 64 KiB corpus complete in under a
  second on a developer machine. A Docker round-trip per seed would push
  the suite into minutes; slow chaos suites stop being run.
- **Determinism.** A real HTTP server's faults aren't reproducible with a
  seed; an in-process adapter's are. When a regression slips in, the
  seed in the assertion message is enough to bisect.

### Past offenders

When this harness catches a regression, record the seed and the commit
it caught in `ChaosHttpAdapter`'s class Javadoc. That paper-trail is
more valuable than any single test method: it shows the harness was
used, not just built.

## Resource ownership and cleanup

`Downloader` owns the `HttpClient` created inside `JdkHttpAdapter` and
closes it in `Downloader.close()`. If an externally provided
`HttpAdapter` is injected and implements `AutoCloseable`, it is also
closed via the pattern-match in `close()`. `ExecutorService` pools in
`downloadAsync` and `downloadChunksParallel` are both used in
try-with-resources (Java 21 `AutoCloseable` support).

## What I skipped, and why

- **HTTP/2 multiplexing**: `HttpClient` negotiates HTTP/2 automatically
  when the server supports it; no extra work needed.
- **Bandwidth throttling**: reduce `parallelism` and `chunkSize` instead
  of adding a token bucket; the levers are already there.
- **Multi-source / torrent-style downloads, mirror failover**: each
  download targets one URI by design; chaos testing was a more useful
  use of the same engineering budget.
- **Resume across `chunkSize` changes**: the manifest invalidates;
  fail-fast wins over reconciliation.
- **GUI / TUI dashboard**: `--report json` is a structured output stream
  any external dashboard can consume.
- **Authentication, request signing, S3-specific paths**: out of scope
  for an ingest primitive — wire your own `HttpAdapter` if you need
  these.
- **A logger framework**: `System.err` for the CLI is fine; the library
  proper takes a `ProgressListener` so the caller decides how (and
  whether) to render anything.
