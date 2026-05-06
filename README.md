# Parallel Range-GET File Downloader

A Java 21 library that downloads large files in parallel using HTTP Range requests. It splits the file into chunks, issues concurrent ranged GETs on virtual threads, writes each chunk directly to its byte offset in a temp file, and atomically renames the temp file to the destination on success. On any failure the temp file is deleted and the destination is never touched.

## Requirements

- Java 21+
- Gradle 8.13+ (wrapper included — no separate install needed)

## Quick start

```bash
./gradlew test                     # fast unit + property + in-process integration tests (no Docker, no network)
./gradlew test -PintegrationTests  # also run Docker-backed Testcontainers tests (requires a Docker host)
```

## Quick demo

The CLI downloads a file in parallel and prints a structured report.

```bash
# 1. Generate a 64 MiB random file
mkdir -p /tmp/corpus && head -c $((64 * 1024 * 1024)) /dev/urandom > /tmp/corpus/test.bin

# 2. Serve it from an Apache container
docker run --rm -d -p 8080:80 -v /tmp/corpus:/usr/local/apache2/htdocs/ --name dl-httpd httpd:2.4

# 3. Download it
./gradlew run --args="--url http://localhost:8080/test.bin --out /tmp/downloaded.bin --report json"

# 4. Tear down
docker stop dl-httpd
```

Sample JSON output:

```json
{"status":"success","file":"/tmp/downloaded.bin","bytes":67108864,"elapsedMs":234,"chunks":8}
```

Add `--sha256 <64-hex-chars>` to verify the body against an expected SHA-256.
A mismatch (e.g. corrupted CDN cache, tampered transit) fails with exit 4 and
the destination is never written:

```bash
$ ./gradlew run --args="--url http://localhost:8080/test.bin --out /tmp/x.bin \
    --sha256 0000000000000000000000000000000000000000000000000000000000000000 --report json"
{"status":"failure","error":"INTEGRITY_FAILURE","exitCode":4,"cause":"integrity check failed: expected SHA-256 0000... got b5d4..."}
```

Run `./gradlew run --args="--help"` for the full flag and exit-code reference.

## Resumability

Pass `--resume` (library: `DownloaderOptions.resumeStrategy(RESUME_IF_VALID)`) and
the downloader will:

- record progress in a sidecar `<dest>.part.json` after every chunk completes
  (atomic flush: write `.tmp`, fsync, rename);
- on a subsequent run, validate that sidecar against the server's `HEAD`
  (URL, ETag — or `Last-Modified` when no ETag — `Content-Length`, and chunk
  size). Match: replay only the missing chunks. Mismatch: fail fast with exit
  `6` / `RESOURCE_CHANGED`, leaving the sidecar in place so the caller can
  decide whether to delete it and retry from scratch.
- send `If-Range` with each ranged GET in resume mode — a `200` response on
  a ranged request now means "the validator no longer matches", surfaced as
  `RESOURCE_CHANGED` rather than silently merging old and new bytes.

```bash
# First attempt is interrupted (Ctrl-C, network drop, etc.)
./gradlew run --args="--url https://example.com/big.bin --out /tmp/big.bin --resume"

# Re-run with the same flag — only missing chunks are re-fetched:
./gradlew run --args="--url https://example.com/big.bin --out /tmp/big.bin --resume"
```

`FRESH` mode (the default) ignores any existing `.part` / `.part.json` files.
Single-stream downloads (servers without `Accept-Ranges`) cannot be resumed and
ignore the flag.

## Progress and reporting

Set a `ProgressListener` on the options (CLI auto-installs one). The listener
receives a sealed `ProgressEvent` stream — `Started`, then `ChunkCompleted` per
chunk, then either `Finished` or `Failed`:

```java
DownloaderOptions opts = DownloaderOptions.builder()
        .progressListener(event -> {
            switch (event) {
                case ProgressEvent.Started s     -> System.out.println("starting " + s.totalBytes() + "B");
                case ProgressEvent.ChunkCompleted c -> System.out.println("chunk " + c.index() + " ok in " + c.duration());
                case ProgressEvent.Finished f    -> System.out.println("done");
                case ProgressEvent.Failed f      -> System.err.println("failed: " + f.error());
            }
        })
        .build();
```

Events are dispatched from a single virtual thread, so listener implementations
do **not** need to be thread-safe with respect to each other. A throwing
listener is caught and the first exception is logged to `System.err` once with
the event class name; the download proceeds.

The CLI installs a live progress line in `--report text` mode (TTY: `\r`-overwriting
`<bytes>/<total> @ <MiB/s> ETA <h:mm:ss> (n/N chunks)`; non-TTY: one line per
chunk). With `--report json` the listener silently aggregates per-chunk
durations and attempts, and the final JSON now includes a `chunkDetails`
array:

```json
{
  "status": "success",
  "file": "/tmp/big.bin",
  "bytes": 67108864,
  "elapsedMs": 234,
  "chunks": 8,
  "sha256": "b5d4...",
  "chunkDetails": [
    {"index": 0, "offset": 0,        "length": 8388608, "attempts": 1, "durationMs": 30},
    {"index": 1, "offset": 8388608,  "length": 8388608, "attempts": 1, "durationMs": 32}
  ]
}
```

Failure JSON has the same `chunkDetails` (capturing chunks that succeeded
before the error), plus `error`, `exitCode`, and `cause`.

## Usage

```java
import com.example.downloader.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

// Synchronous download
DownloaderOptions opts = DownloaderOptions.builder()
        .chunkSize(8 * 1024 * 1024L)   // 8 MiB per chunk
        .parallelism(8)                 // up to 8 concurrent GETs
        .connectTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .maxRetriesPerChunk(3)
        .retryBaseDelay(Duration.ofMillis(200))
        .build();

try (Downloader downloader = new Downloader(opts)) {
    DownloadResult result = downloader.download(
            URI.create("https://example.com/large-file.bin"),
            Path.of("/tmp/large-file.bin")
    );
    switch (result) {
        case DownloadResult.Success s ->
            System.out.printf("Downloaded %d bytes in %s (%d chunks)%n",
                    s.bytes(), s.elapsed(), s.chunks());
        case DownloadResult.Failure f ->
            System.err.println("Failed: " + f.error() + " — " + f.cause().getMessage());
    }
}

// Async with cancellation
try (Downloader downloader = new Downloader(DownloaderOptions.defaults())) {
    DownloadHandle handle = downloader.downloadAsync(
            URI.create("https://example.com/huge.bin"),
            Path.of("/tmp/huge.bin")
    );
    Thread.sleep(5_000);
    if (handle.state() == DownloadHandle.State.RUNNING) handle.cancel();
    DownloadResult result = handle.join();
}
```

## Key behaviours

| Scenario | Behaviour |
|---|---|
| Server supports `Accept-Ranges: bytes` | Parallel range-GET; concurrency bounded by `parallelism` |
| Server ignores Range header (returns `200`) | Detected via probe chunk; falls back to single-stream; no corruption possible |
| `206` with mismatched `Content-Range` | Detected and rejected before commit |
| Transient errors (408/429/5xx, `IOException`, timeout) | Exponential backoff with full jitter; honours `Retry-After` |
| Non-retryable errors (400/401/403/404/…) | Immediate typed failure |
| Cancellation | Cooperative: observed between buffer reads and before each chunk attempt; in FRESH mode `.part` deleted, in `RESUME_IF_VALID` mode preserved; destination untouched |
| Any failure | FRESH: `.part` and `.part.json` deleted. RESUME_IF_VALID: both preserved so the user can retry `--resume`. Destination never written or corrupted in either mode. |
| Destination already exists | Success replaces it atomically; failure leaves original intact |
| Destination is a directory | Immediate typed `IO_ERROR` |
| Resource changed mid-resume (ETag, Content-Length, chunk size, or URL drift) | Detected via `If-Range` and manifest validation; fails fast with `RESOURCE_CHANGED` (exit 6); sidecar preserved |

## Default options

| Option | Default | Notes |
|---|---|---|
| `chunkSize` | 8 MiB | Amortises TCP slow-start |
| `parallelism` | 8 | Caps concurrent GETs; sweet spot for a single CDN host |
| `connectTimeout` | 10 s | |
| `requestTimeout` | 60 s | Per chunk including body transfer |
| `maxRetriesPerChunk` | 3 | |
| `retryBaseDelay` | 200 ms | Exponential + full jitter, capped at 30 s |

## Public API

```
Downloader            — download(URI, Path) / downloadAsync(URI, Path) / close()
DownloaderOptions     — record + Builder; expectedDigest(Algorithm, byte[])
                                          ; resumeStrategy(ResumeStrategy)
                                          ; progressListener(ProgressListener)
DownloadResult        — sealed: Success | Failure; Success.sha256() : Optional<byte[]>
DownloadError         — enum: HTTP_ERROR | IO_ERROR | SIZE_MISMATCH | INTEGRITY_FAILURE
                              | RESOURCE_CHANGED | CANCELLED | TIMEOUT | RANGES_NOT_SUPPORTED
DownloadHandle        — join() / joinWithTimeout(Duration) / cancel() / state()
HttpAdapter           — inject a custom adapter (e.g. for tests)
HttpStatusException   — Failure.cause() for HTTP_ERROR; carries statusCode()
Algorithm             — enum: SHA_256 (single member; extensible)
ExpectedDigest        — record (algorithm, bytes); validated in compact ctor
ResumeStrategy        — enum: FRESH (default) | RESUME_IF_VALID
ProgressListener      — onProgress(ProgressEvent); NO_OP default
ProgressEvent         — sealed: Started | ChunkCompleted | Failed | Finished
cli.Main              — entry point for ./gradlew run
```

## Design

See [DESIGN.md](DESIGN.md) for decisions, edge-case analysis, and trade-offs.
