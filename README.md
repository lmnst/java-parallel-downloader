# Parallel Range-GET File Downloader

[![CI](https://github.com/lmnst/LLM-Telemetry-PII-Validator/actions/workflows/ci.yml/badge.svg)](https://github.com/lmnst/LLM-Telemetry-PII-Validator/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21+-orange?logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

```java
try (Downloader dl = new Downloader(opts)) {
    DownloadResult result = dl.download(uri, dest);
}
```

A Java 21 library and CLI for parallel, resumable, HTTP file
downloads. The shape is conventional; the value is in one invariant
the implementation refuses to break.

> **The invariant.** Either `dest` ends up holding bytes whose
> SHA-256 matches the expected value, or no file appears at `dest`
> and a typed error is returned. There is no in-between state, even
> on crash, even under arbitrary HTTP fault injection.

The next sections show the artifact in motion, then explain the
three pieces of code that make the invariant true.

## Run it

```bash
./gradlew installDist
DL=build/install/parallel-downloader/bin/parallel-downloader

# Local server with a 64 MiB random corpus
mkdir -p /tmp/corpus
head -c $((64 * 1024 * 1024)) /dev/urandom > /tmp/corpus/test.bin
docker run --rm -d -p 8080:80 \
    -v /tmp/corpus:/usr/local/apache2/htdocs/ \
    --name dl-httpd httpd:2.4

$DL --url http://localhost:8080/test.bin --out /tmp/dl.bin --report json
```

```json
{
  "status": "success",
  "file": "/tmp/dl.bin",
  "bytes": 67108864,
  "elapsedMs": 234,
  "chunks": 8
}
```

`just demo` packages the same flow with a generated SHA-256 check
and full teardown.

## Anatomy of a download

![Download lifecycle sequence diagram](docs/architecture.svg)

PlantUML source: [`docs/architecture.puml`](docs/architecture.puml).

Three pieces of code make the invariant load-bearing rather than
aspirational. Each one corresponds to a labelled phase in the
diagram above.

### Probe chunk

Some servers advertise `Accept-Ranges: bytes` in HEAD but ignore
the `Range` header on the subsequent GET, returning the full body
with status `200`. If N parallel chunks each receive the full body
and write at their own offset, the destination file is corrupted
silently. The probe chunk runs a synchronous GET at offset 0
*before* any other chunks fan out. A `200` is treated as the
complete download and committed; no parallel writes ever happen,
so corruption is impossible. A `206` confirms range support, and
the remaining chunks fan out.

### Integrity gate

When `expectedDigest(...)` is set, the temp file is streamed
through a `MessageDigest` after the last chunk completes and
*before* `Files.move(..., ATOMIC_MOVE)`. A mismatch fails with
`INTEGRITY_FAILURE` and deletes the temp file. The destination
path is never touched on failure, so a downstream watcher cannot
observe a wrong-bytes file under the right name, even transiently.

### Resumption fence

In `RESUME_IF_VALID` mode, a `<dest>.part.meta` sidecar manifest is
written and `fsync`ed after each chunk's successful write. It
records URL, ETag (or `Last-Modified`), `Content-Length`, chunk
size, and a hex bitmap of completed chunks. On retry, only missing
chunks are re-fetched, and every ranged GET carries
`If-Range: <validator>`. A `200` on a ranged GET with `If-Range`
set means the server has replaced the resource; the adapter
surfaces this and the downloader fails fast with `RESOURCE_CHANGED`
rather than splicing old and new bytes.

## Speed

64 MiB file served from `httpd:2.4` with 50 ms one-way `netem`
delay, 4 MiB chunks, median of three runs. Reproducible without
Docker via `./gradlew jmh`.

| `--parallelism` | Median time | Throughput | Speedup |
|---:|---:|---:|---:|
| 1 (single-stream) | 2929 ms | 21.8 MiB/s | 1.00x |
| 4                 | 1298 ms | 49.3 MiB/s | 2.26x |
| 8                 |  995 ms | 64.3 MiB/s | 2.94x |
| 16                |  827 ms | 77.4 MiB/s | 3.54x |

The shape of the curve is the load-bearing claim, not the absolute
numbers. Speedup compounds until the BDP of the link is filled.

For a zero-RTT loopback comparison against `curl` and `wget`, see
[`docs/COMPARISON.md`](docs/COMPARISON.md).

## API surface

```java
try (Downloader dl = new Downloader(DownloaderOptions.builder()
        .parallelism(8)
        .chunkSize(8 * 1024 * 1024L)
        .expectedDigest(Algorithm.SHA_256, expectedBytes)
        .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
        .build())) {

    DownloadResult result = dl.download(uri, dest);
    switch (result) {
        case DownloadResult.Success s -> System.out.println(s.bytes());
        case DownloadResult.Failure f -> System.err.println(f.error());
    }
}
```

| Type | Role |
|---|---|
| `Downloader` | `download` / `downloadAsync` / `close`. |
| `DownloaderOptions` | Record + builder. `expectedDigest`, `resumeStrategy`, `progressListener`. |
| `DownloadResult` | Sealed: `Success` or `Failure`. |
| `DownloadError` | `HTTP_ERROR`, `IO_ERROR`, `SIZE_MISMATCH`, `INTEGRITY_FAILURE`, `RESOURCE_CHANGED`, `CANCELLED`, `TIMEOUT`, `RANGES_NOT_SUPPORTED`. |
| `Algorithm` / `ExpectedDigest` | Algorithm enum (`SHA_256`) and the expected-digest record passed to `expectedDigest(...)`. |
| `ProgressListener` | SPI; `NO_OP` is the default. |
| `ProgressEvent` | Sealed: `Started`, `ChunkCompleted`, `Failed`, `Finished`. |

CLI flag reference and exit codes are in
[`docs/USAGE.md`](docs/USAGE.md).

## Tests

| Layer | Tests | Run with | What it covers |
|---|---:|---|---|
| Default (unit, property, in-process integration) | 163 | `./gradlew test` | Range planner, manifest, retry policy, file assembler, downloader, integrity, progress, CLI parser, plus a 40-case property pass over random sizes and chunk sizes and a 10-case in-process suite against the JDK `HttpServer`. |
| Chaos (added by `-PchaosTests`) | 121 | `./gradlew test -PchaosTests` | 120 seeded `ChaosPropertyTest` runs (14 fault classes per GET) plus one `ChaosResumeTest` that composes chaos with resume. Chaos opts in *on top of* the default 163, so the gradle task runs 284 tests total. |
| Docker integration (added by `-PintegrationTests`) | 1 | `RUN_DOCKER_IT=1 ./gradlew test -PintegrationTests` | End-to-end through the installed CLI against `httpd:2.4` via Testcontainers. The `RUN_DOCKER_IT=1` gate makes Docker absence a hard failure rather than a silent skip. |

Property tests are folded into the default suite (`PropertyTest` is one
of the 163), not split out into a separate run. The chaos suite is
opt-in because the invariant it asserts is about *combinations* of
failures (a `503` on chunk 0, a truncated body on chunk 4, a malformed
`Content-Range` on chunk 7, all in the same run). 14 fault classes
times 8 chunks per run is a combinatorial space too large to enumerate
by hand; a property test seeds the RNG and lets the harness explore.
When it finds a bug the seed in the failure message replays the exact
sequence.

## Build

```bash
./gradlew check                # all tests except chaos
./gradlew test -PchaosTests    # chaos suite (~30 s)
./gradlew installDist          # CLI launcher under build/install/
./gradlew javadoc              # publishable API docs
```

CI runs the full check on Linux, macOS, and Windows on every push.
Requires Java 21 or newer. The Gradle 8.13 wrapper is included; no
system Gradle install is needed.

The non-test runtime has no third-party dependencies.

## Reading on

- [`DESIGN.md`](DESIGN.md) for trade-offs, the resumption state
  machine, and what was deliberately left out.
- [`docs/USAGE.md`](docs/USAGE.md) for the full CLI reference and
  exit-code table.
- [`docs/COMPARISON.md`](docs/COMPARISON.md) for the loopback
  benchmark against `curl` and `wget`.
- [`docs/STORY-TESTCONTAINERS-DOCKER.md`](docs/STORY-TESTCONTAINERS-DOCKER.md)
  for a debugging episode about a silent integration-test skip on
  Docker Engine 29.

## License

MIT. See [`LICENSE`](LICENSE).
