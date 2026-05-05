# Parallel Range-GET File Downloader

A Java 21 library that downloads large files in parallel using HTTP Range requests. It splits the file into chunks, issues concurrent ranged GETs on virtual threads, writes each chunk directly to its byte offset in a temp file, and atomically renames the temp file to the destination on success. On any failure the temp file is deleted and the destination is never touched.

## Requirements

- Java 21+
- Gradle 8.13+ (wrapper included — no separate install needed)

## Quick start

```bash
./gradlew test          # compile and run the full test suite (no Docker, no network)
./gradlew test --info   # verbose output
```

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
| Cancellation | Cooperative: observed between buffer reads and before each chunk attempt; temp file deleted; destination untouched; no socket-level abort |
| Any failure | `.part` temp file deleted; destination never written or corrupted |
| Destination already exists | Success replaces it atomically; failure leaves original intact |
| Destination is a directory | Immediate typed `IO_ERROR` |

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
Downloader          — download(URI, Path) / downloadAsync(URI, Path) / close()
DownloaderOptions   — record + Builder
DownloadResult      — sealed: Success | Failure
DownloadError       — enum: HTTP_ERROR | IO_ERROR | SIZE_MISMATCH | CANCELLED | TIMEOUT | RANGES_NOT_SUPPORTED
DownloadHandle      — join() / joinWithTimeout(Duration) / cancel() / state()
HttpAdapter         — inject a custom adapter (e.g. for tests)
```

## Design

See [DESIGN.md](DESIGN.md) for decisions, edge-case analysis, and trade-offs.
