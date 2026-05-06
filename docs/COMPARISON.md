# Comparison: parallel-downloader vs curl, wget

> Re-derive these numbers locally with [`docs/run-comparison.sh`](run-comparison.sh).

This is the **zero-RTT, single-host** comparison: an Apache `httpd:2.4`
container on the loopback interface, three file sizes, `hyperfine --warmup
1 --runs 5` per tool. It is, deliberately, the unflattering regime for a
parallel downloader. Splitting one URL across eight connections cannot beat
a single-connection client when there is no round-trip latency to amortize;
the ranged-GET, sidecar-manifest, fsync-per-chunk machinery is pure
overhead in this setup.

The README's [Performance section](../README.md#performance) covers the
intended regime (`netem` 50 ms one-way delay), where the same machinery
buys ~3x speedup at `parallelism=8`. That is the curve the tool is built
to win; this page documents the absolute-throughput floor on a happy
network so a reader has a recognizable anchor for the README's relative
numbers.

Hardware: Apple Silicon laptop (May 2026), Docker Engine 29.0.1, Apache
httpd 2.4.67, hyperfine 1.20.0, curl 8.7.1, wget 1.25.0. The
`parallel-downloader` runs are a cold JVM launch per iteration. Each tool
writes to a different output path so hyperfine's repeats don't trip a
file-already-exists race.

## 10 MiB

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 204.8 +/- 11.8 | 192.4 | 222.9 | 17.63 +/- 1.16 |
| `curl --parallel-max 8` | 13.3 +/- 1.7 | 11.8 | 15.9 | 1.15 +/- 0.15 |
| `wget` | 11.6 +/- 0.4 | 11.2 | 12.2 | 1.00 |

The 10 MiB row is dominated by JVM cold-start, not download work. A
loopback download of 10 MiB takes ~12 ms; the rest of the
parallel-downloader time (~190 ms) is the JVM warming up. Hyperfine
measures wall-clock per invocation, and our CLI starts a fresh JVM
every time, so we pay that tax on every sample. In real ingest
pipelines, the JVM is launched once and downloads many files; the
amortized per-file cost matches the 100 / 1024 MiB rows below.

## 100 MiB

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 330.4 +/- 15.3 | 314.3 | 353.4 | 5.36 +/- 0.45 |
| `curl --parallel-max 8` | 67.6 +/- 5.8 | 61.1 | 74.3 | 1.10 +/- 0.12 |
| `wget` | 61.7 +/- 4.3 | 58.8 | 69.0 | 1.00 |

JVM startup is now ~50% of the wall time; the remaining ~140 ms covers
twelve 8 MiB chunks at default settings, including the sidecar
manifest path even though `--resume` is off (the `Manifest` allocation
is cheap, but per-chunk progress dispatch and `Content-Range`
validation are all running). curl and wget run as a single connection
and saturate loopback throughput.

## 1024 MiB

| Command | Mean [s] | Min [s] | Max [s] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 1.903 +/- 0.158 | 1.702 | 2.088 | 3.14 +/- 0.34 |
| `curl --parallel-max 8` | 0.651 +/- 0.053 | 0.602 | 0.740 | 1.08 +/- 0.12 |
| `wget` | 0.605 +/- 0.043 | 0.567 | 0.678 | 1.00 |

JVM startup is ~10% of the 1024 MiB run; the remaining gap is steady-state
throughput. wget pulls ~1.7 GB/s on loopback, parallel-downloader pulls
~540 MB/s. The 3x gap reflects per-chunk overhead (range planning,
`MessageDigest` streaming pass, `FileChannel.force(true)` per chunk's
write batch, atomic move) that is amortized over many fewer bytes per
chunk than the curl/wget single-connection writes.

## When this matters, and when it doesn't

For a single host with negligible network distance, a single-connection
client is the right tool. `parallel-downloader` is built for the regime
where:

- One-way RTT is non-trivial (50 ms+ in the README's measured case;
  a parallel-of-eight at 50 ms RTT amortizes ~3x faster than a single
  connection over the same link).
- The download is large enough that JVM startup is irrelevant.
- The caller wants integrity verification (`--sha256`), resumability
  (`--resume`), or chaos-tested correctness guarantees that single
  curl/wget invocations do not provide.

If none of those apply, the 1024 MiB row above is honest: curl wins.

`curl --parallel-max 8` is shown here for completeness because the brief
asked for it; with a single source URL, curl runs one connection
regardless of the parallelism flag, so this is effectively a
single-connection curl baseline. The 5 to 10% gap to wget across rows is
inter-tool overhead, not parallelism.

## Reproducing

```bash
docs/run-comparison.sh
```

The script builds `installDist`, generates 10/100/1024 MiB random files,
spins up `httpd:2.4`, runs hyperfine against the three tools, and tears
down. It pins `JAVA_HOME` to the Gradle toolchain's JDK 21 so the CLI
launches the same JVM that compiled it (avoiding a class-file-65
mismatch when the operator's PATH points at JDK 17).
