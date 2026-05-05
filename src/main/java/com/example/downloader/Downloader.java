package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Downloader implements AutoCloseable {

    // RFC 9110 §14.4: Content-Range: bytes start-end/total  (total may be *)
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final DownloaderOptions options;
    private final HttpAdapter http;

    public Downloader(DownloaderOptions options) {
        this.options = options;
        this.http = new JdkHttpAdapter(options);
    }

    Downloader(DownloaderOptions options, HttpAdapter http) {
        this.options = options;
        this.http = http;
    }

    // ── public API ───────────────────────────────────────────────────────────

    public DownloadResult download(URI source, Path destination) throws InterruptedException {
        CancelToken cancel = new CancelToken();
        return doDownload(source, destination, cancel);
    }

    public DownloadHandle downloadAsync(URI source, Path destination) {
        CancelToken cancel = new CancelToken();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Future<DownloadResult> future = exec.submit(() -> doDownload(source, destination, cancel));
        exec.shutdown();
        return new DownloadHandle(future, cancel);
    }

    @Override
    public void close() {
        if (http instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    // ── core logic ───────────────────────────────────────────────────────────

    private DownloadResult doDownload(URI uri, Path dest, CancelToken cancel) {
        long startNanos = System.nanoTime();

        HttpAdapter.HeadResponse head;
        try {
            head = http.head(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(DownloadError.CANCELLED, e);
        } catch (IOException e) {
            return failure(DownloadError.IO_ERROR, e);
        }

        if (head.status() < 200 || head.status() > 299) {
            return failure(DownloadError.HTTP_ERROR,
                    new IOException("HEAD returned HTTP " + head.status()));
        }

        boolean canParallel = head.acceptRanges()
                && head.contentLength() > 0
                && options.parallelism() > 1;

        FileAssembler asm;
        try {
            asm = new FileAssembler(dest);
        } catch (IOException e) {
            return failure(DownloadError.IO_ERROR, e);
        }

        try {
            if (canParallel) {
                return parallelDownload(uri, dest, head, asm, cancel, startNanos);
            } else {
                return singleStreamDownload(uri, dest, asm, cancel, startNanos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asm.abort();
            return failure(DownloadError.CANCELLED, e);
        } catch (SizeMismatchException e) {
            asm.abort();
            return failure(DownloadError.SIZE_MISMATCH, e);
        } catch (HttpStatusException e) {
            asm.abort();
            return failure(DownloadError.HTTP_ERROR, e);
        } catch (IOException e) {
            asm.abort();
            return failure(DownloadError.IO_ERROR, e);
        } finally {
            asm.close(); // safe — abort/commit already happened; close() is idempotent
        }
    }

    private DownloadResult parallelDownload(URI uri, Path dest, HttpAdapter.HeadResponse head,
                                            FileAssembler asm, CancelToken cancel,
                                            long startNanos)
            throws IOException, InterruptedException {

        List<ByteRange> ranges = RangePlanner.plan(head.contentLength(), options.chunkSize());
        RetryPolicy retry = new RetryPolicy(options);

        // Probe chunk 0 synchronously: detect servers that advertise Range support
        // but return 200 + full body on a ranged GET.
        ByteRange firstRange = ranges.get(0);
        HttpAdapter.GetResponse probe = downloadChunk(0, firstRange, uri, asm, cancel, retry);

        if (probe.status() == 200) {
            // Server returned full body at offset 0; no other chunks have run so no corruption.
            long total = probe.bytesWritten();
            if (head.contentLength() > 0 && total != head.contentLength()) {
                throw new SizeMismatchException(head.contentLength(), total);
            }
            asm.commit();
            return success(dest, total, startNanos, 1);
        }

        if (probe.status() != 206) {
            throw new HttpStatusException(probe.status());
        }

        // Validate Content-Range of probe before trusting its data.
        validateContentRange(probe.contentRangeHeader(), firstRange, head.contentLength());

        // Verify the server sent the exact number of bytes we requested.
        // Without this check a truncated body would silently leave zeros in the
        // unwritten region of the temp file (most filesystems extend with zeros
        // on a positional write past EOF).
        if (probe.bytesWritten() != firstRange.length()) {
            throw new IOException("probe chunk truncated: got " + probe.bytesWritten()
                    + " of " + firstRange.length() + " bytes");
        }

        if (ranges.size() > 1) {
            downloadChunksParallel(uri, ranges.subList(1, ranges.size()), asm, cancel, retry,
                    head.contentLength());
        }

        if (cancel.isCancelled()) throw new InterruptedException("cancelled after chunks");

        long expected = head.contentLength();
        // RangePlanner guarantees sum(range.length) == contentLength, and per-chunk
        // truncation checks above ensure each chunk received its full body, so this
        // sum will equal expected whenever those checks pass. Kept as a safety net.
        long totalWritten = ranges.stream().mapToLong(ByteRange::length).sum();
        if (expected > 0 && totalWritten != expected) {
            throw new SizeMismatchException(expected, totalWritten);
        }

        asm.commit();
        return success(dest, expected, startNanos, ranges.size());
    }

    private void downloadChunksParallel(URI uri, List<ByteRange> ranges,
                                        FileAssembler asm, CancelToken cancel,
                                        RetryPolicy retry, long expectedTotal)
            throws IOException, InterruptedException {

        // Semaphore caps actual concurrent GETs to options.parallelism().
        // Virtual threads block cheaply while waiting for a permit.
        Semaphore sem = new Semaphore(options.parallelism());
        List<Callable<Void>> tasks = new ArrayList<>(ranges.size());

        for (int i = 0; i < ranges.size(); i++) {
            final int idx = i + 1; // 0 was the probe
            final ByteRange range = ranges.get(i);
            tasks.add(() -> {
                sem.acquire();
                try {
                    HttpAdapter.GetResponse resp = downloadChunk(idx, range, uri, asm, cancel, retry);
                    if (resp.status() != 206) {
                        throw new HttpStatusException(resp.status());
                    }
                    validateContentRange(resp.contentRangeHeader(), range, expectedTotal);
                    if (resp.bytesWritten() != range.length()) {
                        throw new IOException("chunk " + idx + " truncated: got "
                                + resp.bytesWritten() + " of " + range.length() + " bytes");
                    }
                    return null;
                } finally {
                    sem.release();
                }
            });
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            collectFutures(futures);
        }
    }

    /**
     * Joins all futures, collecting the first error. When any error is detected,
     * cancels and drains remaining futures so that virtual threads blocked on the
     * semaphore are not stranded when the executor's close() is called.
     */
    private void collectFutures(List<Future<Void>> futures)
            throws IOException, InterruptedException {
        IOException firstError = null;
        boolean interrupted = false;

        for (int i = 0; i < futures.size(); i++) {
            Future<Void> f = futures.get(i);
            // Once we have a definitive failure, cancel remaining tasks so they exit
            // quickly (unblocking any waiting on the semaphore) rather than letting
            // pool.close() wait for them indefinitely.
            if (firstError != null || interrupted) {
                f.cancel(true);
                try { f.get(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | CancellationException ignored) {}
                continue;
            }
            try {
                f.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException) {
                    interrupted = true;
                } else if (firstError == null) {
                    firstError = cause instanceof IOException ioe ? ioe
                            : new IOException("chunk failed", cause);
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("chunk download interrupted");
        }
        if (firstError != null) throw firstError;
    }

    private DownloadResult singleStreamDownload(URI uri, Path dest,
                                                FileAssembler asm, CancelToken cancel,
                                                long startNanos)
            throws IOException, InterruptedException {

        RetryPolicy retry = new RetryPolicy(options);
        HttpAdapter.GetResponse resp = downloadChunk(0, null, uri, asm, cancel, retry);

        if (resp.status() != 200 && resp.status() != 206) {
            throw new HttpStatusException(resp.status());
        }

        asm.commit();
        return success(dest, resp.bytesWritten(), startNanos, 1);
    }

    /**
     * Downloads one chunk with retry. A null range means "no Range header" (single-stream).
     * Returns the GetResponse on success; throws on permanent failure.
     */
    private HttpAdapter.GetResponse downloadChunk(int chunkIndex, ByteRange range,
                                                   URI uri, FileAssembler asm,
                                                   CancelToken cancel, RetryPolicy retry)
            throws IOException, InterruptedException {

        for (int attempt = 0; ; attempt++) {
            if (cancel.isCancelled()) throw new InterruptedException("cancelled before chunk " + chunkIndex);

            ChunkSink sink = asm.sinkAt(range == null ? 0 : range.offset());

            try {
                HttpAdapter.GetResponse resp = http.get(uri, range, sink, cancel);

                if (isRetryableStatus(resp.status())) {
                    Duration retryAfter = Duration.ZERO;
                    Optional<Duration> delay = retry.evaluate(attempt,
                            new RetryPolicy.Trigger.HttpStatus(resp.status(), retryAfter));
                    if (delay.isEmpty()) throw new HttpStatusException(resp.status());
                    Thread.sleep(delay.get().toMillis());
                    continue;
                }

                return resp;

            } catch (InterruptedException e) {
                throw e; // cancellation — do not retry
            } catch (IOException e) {
                Optional<Duration> delay = retry.evaluate(attempt,
                        new RetryPolicy.Trigger.IoFailure(e));
                if (delay.isEmpty()) throw e;
                Thread.sleep(delay.get().toMillis());
            }
        }
    }

    /**
     * Validates a 206 Content-Range header against the range we requested.
     * Missing header is tolerated (some servers omit it for single-range responses).
     * A present but mismatched header always fails — silent offset corruption is not acceptable.
     */
    private static void validateContentRange(String header, ByteRange range, long expectedTotal)
            throws IOException {
        if (header == null) return;
        Matcher m = CONTENT_RANGE_PATTERN.matcher(header.trim());
        if (!m.matches()) {
            throw new IOException("malformed Content-Range: " + header);
        }
        long start = Long.parseLong(m.group(1));
        long end   = Long.parseLong(m.group(2));
        if (start != range.offset()) {
            throw new IOException("Content-Range start mismatch: got " + start
                    + ", expected " + range.offset());
        }
        if (end != range.lastByte()) {
            throw new IOException("Content-Range end mismatch: got " + end
                    + ", expected " + range.lastByte());
        }
        if (!"*".equals(m.group(3)) && expectedTotal > 0) {
            long total = Long.parseLong(m.group(3));
            if (total != expectedTotal) {
                throw new IOException("Content-Range total mismatch: got " + total
                        + ", expected " + expectedTotal);
            }
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DownloadResult.Success success(Path dest, long bytes, long startNanos, int chunks) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return new DownloadResult.Success(dest, bytes, elapsed, chunks);
    }

    private static DownloadResult.Failure failure(DownloadError error, Throwable cause) {
        return new DownloadResult.Failure(error, cause);
    }

    // ── package-private exceptions ───────────────────────────────────────────

    static final class HttpStatusException extends IOException {
        final int statusCode;
        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    static final class SizeMismatchException extends IOException {
        SizeMismatchException(long expected, long actual) {
            super("size mismatch: expected " + expected + ", got " + actual);
        }
    }
}
