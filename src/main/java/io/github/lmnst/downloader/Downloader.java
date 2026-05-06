package io.github.lmnst.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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

/**
 * Parallel range-GET file downloader. Use {@link #download} for a synchronous
 * call or {@link #downloadAsync} for one that returns a {@link DownloadHandle}
 * the caller can cancel. Configuration (chunk size, parallelism, retries,
 * integrity, resume strategy, progress listener) is supplied via
 * {@link DownloaderOptions}.
 *
 * <p>This class is {@link AutoCloseable}: closing it releases the underlying
 * {@code HttpClient}. Multiple downloads can run on a single instance.
 */
public final class Downloader implements AutoCloseable {

    // RFC 9110 §14.4: Content-Range: bytes start-end/total  (total may be *)
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final DownloaderOptions options;
    private final HttpAdapter http;

    /**
     * Creates a Downloader using the JDK's {@code java.net.http.HttpClient}.
     *
     * @param options download configuration
     */
    public Downloader(DownloaderOptions options) {
        this.options = options;
        this.http = new JdkHttpAdapter(options);
    }

    Downloader(DownloaderOptions options, HttpAdapter http) {
        this.options = options;
        this.http = http;
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Downloads {@code source} to {@code destination} synchronously.
     *
     * @param source      the URI to fetch
     * @param destination the file path to write
     * @return            the typed result of the operation
     * @throws InterruptedException if the calling thread is interrupted
     */
    public DownloadResult download(URI source, Path destination) throws InterruptedException {
        CancelToken cancel = new CancelToken();
        return doDownload(source, destination, cancel);
    }

    /**
     * Starts an asynchronous download and returns a handle for cancellation
     * and result retrieval. The handle's underlying virtual thread runs
     * independently of the caller.
     *
     * @param source      the URI to fetch
     * @param destination the file path to write
     * @return            a handle for the running download
     */
    public DownloadHandle downloadAsync(URI source, Path destination) {
        CancelToken cancel = new CancelToken();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Future<DownloadResult> future = exec.submit(() -> doDownload(source, destination, cancel));
        exec.shutdown();
        return new DownloadHandle(future, cancel);
    }

    /**
     * Releases the underlying HTTP client and any custom adapter that
     * implements {@link AutoCloseable}. Idempotent.
     */
    @Override
    public void close() {
        if (http instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    // ── core logic ───────────────────────────────────────────────────────────

    private DownloadResult doDownload(URI uri, Path dest, CancelToken cancel) {
        try (ProgressDispatcher progress = new ProgressDispatcher(options.progressListener())) {
            DownloadResult result = doDownloadInner(uri, dest, cancel, progress);
            if (result instanceof DownloadResult.Success s) {
                progress.emit(new ProgressEvent.Finished(s.bytes(), s.elapsed(), s.chunks()));
            } else if (result instanceof DownloadResult.Failure f) {
                progress.emit(new ProgressEvent.Failed(f.error(), f.cause()));
            }
            return result;
        }
    }

    private DownloadResult doDownloadInner(URI uri, Path dest, CancelToken cancel,
                                           ProgressDispatcher progress) {
        long startNanos = System.nanoTime();

        HttpAdapter.HeadResponse head;
        try {
            head = http.head(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(DownloadError.CANCELLED, e);
        } catch (HttpTimeoutException e) {
            return failure(DownloadError.TIMEOUT, e);
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

        // Resume only applies in parallel mode, single-stream has nothing to resume.
        boolean resumeMode = options.resumeStrategy() == ResumeStrategy.RESUME_IF_VALID
                && canParallel;

        List<ByteRange> ranges = canParallel
                ? RangePlanner.plan(head.contentLength(), options.chunkSize())
                : null;
        int chunkCount = canParallel ? ranges.size() : 1;

        progress.emit(new ProgressEvent.Started(head.contentLength(), chunkCount));

        FileAssembler asm;
        try {
            asm = new FileAssembler(dest, resumeMode);
        } catch (IOException e) {
            return failure(DownloadError.IO_ERROR, e);
        }

        try {
            if (canParallel) {
                return parallelDownload(uri, dest, head, ranges, asm, resumeMode,
                        cancel, startNanos, progress);
            } else {
                return singleStreamDownload(uri, dest, asm, cancel, startNanos, progress);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asm.abort();
            return failure(DownloadError.CANCELLED, e);
        } catch (SizeMismatchException e) {
            asm.abort();
            return failure(DownloadError.SIZE_MISMATCH, e);
        } catch (IntegrityException e) {
            asm.abort();
            return failure(DownloadError.INTEGRITY_FAILURE, e);
        } catch (ResourceChangedException e) {
            asm.abort();
            return failure(DownloadError.RESOURCE_CHANGED, e);
        } catch (HttpStatusException e) {
            asm.abort();
            return failure(DownloadError.HTTP_ERROR, e);
        } catch (HttpTimeoutException e) {
            asm.abort();
            return failure(DownloadError.TIMEOUT, e);
        } catch (IOException e) {
            asm.abort();
            return failure(DownloadError.IO_ERROR, e);
        } finally {
            asm.close(); // safe, abort/commit already happened; close() is idempotent
        }
    }

    private DownloadResult parallelDownload(URI uri, Path dest, HttpAdapter.HeadResponse head,
                                            List<ByteRange> ranges, FileAssembler asm,
                                            boolean resumeMode, CancelToken cancel,
                                            long startNanos, ProgressDispatcher progress)
            throws IOException, InterruptedException {

        RetryPolicy retry = new RetryPolicy(options);
        ResumeContext resume = setupOrLoadManifest(uri, head, asm, resumeMode);
        Manifest manifest = resume == null ? null : resume.manifest();
        String ifRange    = resume == null ? null : resume.ifRange();

        ByteRange firstRange = ranges.get(0);
        boolean firstChunkAlreadyDone = manifest != null && manifest.isCompleted(0);
        if (!firstChunkAlreadyDone) {
            ProbeOutcome probe = probeFirstChunk(uri, head, firstRange, asm, cancel,
                    retry, ifRange, progress, manifest);
            if (probe.fullDownload()) {
                Optional<byte[]> sha = verifyAndCommit(asm);
                return success(dest, probe.bytesWritten(), startNanos, 1, sha);
            }
        }

        if (ranges.size() > 1) {
            downloadChunksParallel(uri, ranges, asm, cancel, retry,
                    head.contentLength(), manifest, ifRange, progress);
        }

        if (cancel.isCancelled()) throw new InterruptedException("cancelled after chunks");

        long expected = head.contentLength();
        long totalWritten = ranges.stream().mapToLong(ByteRange::length).sum();
        if (expected > 0 && totalWritten != expected) {
            throw new SizeMismatchException(expected, totalWritten);
        }

        Optional<byte[]> sha = verifyAndCommit(asm);
        return success(dest, expected, startNanos, ranges.size(), sha);
    }

    /**
     * Reads or creates the resume sidecar in {@code RESUME_IF_VALID} mode and
     * returns the manifest plus the {@code If-Range} validator. Returns
     * {@code null} when {@code resumeMode} is false. Throws
     * {@link ResourceChangedException} if an existing manifest disagrees with
     * the current HEAD (URL, validators, content length, or chunk size).
     */
    private ResumeContext setupOrLoadManifest(URI uri, HttpAdapter.HeadResponse head,
                                              FileAssembler asm, boolean resumeMode)
            throws IOException {
        if (!resumeMode) return null;

        Path manifestPath = asm.manifestFile();
        Manifest manifest;
        if (Files.exists(manifestPath)) {
            Manifest existing;
            try {
                existing = Manifest.read(manifestPath);
            } catch (IOException e) {
                throw new ResourceChangedException("manifest unreadable: " + e.getMessage());
            }
            if (!Objects.equals(existing.url, uri)) {
                throw new ResourceChangedException(
                        "manifest URL " + existing.url + " differs from " + uri);
            }
            if (!existing.matchesHead(head, options.chunkSize())) {
                throw new ResourceChangedException(
                        "manifest validators no longer match HEAD response");
            }
            manifest = existing;
        } else {
            manifest = Manifest.forNewDownload(uri, head, options.chunkSize());
            manifest.writeAtomically(manifestPath);
        }
        return new ResumeContext(manifest, manifest.etag); // null etag = no If-Range header
    }

    /**
     * Synchronously fetches chunk 0 to detect the 200-on-ranged-GET hazard
     * before any other chunk fans out. Returns {@link ProbeOutcome#fullDownload()}
     * when the server returned the entire body (single-chunk path); otherwise
     * validates the 206 response's {@code Content-Range} and byte count, marks
     * the chunk complete in the manifest if one is present, and returns a
     * partial-mode outcome so the caller proceeds to fan out the rest.
     */
    private ProbeOutcome probeFirstChunk(URI uri, HttpAdapter.HeadResponse head,
                                         ByteRange firstRange, FileAssembler asm,
                                         CancelToken cancel, RetryPolicy retry,
                                         String ifRange, ProgressDispatcher progress,
                                         Manifest manifest)
            throws IOException, InterruptedException {

        HttpAdapter.GetResponse probe =
                downloadChunk(0, firstRange, uri, asm, cancel, retry, ifRange, progress);

        if (probe.ifRangeMismatch()) {
            throw new ResourceChangedException(
                    "If-Range validator mismatch: server returned 200 on a ranged GET");
        }

        if (probe.status() == 200) {
            long total = probe.bytesWritten();
            if (head.contentLength() > 0 && total != head.contentLength()) {
                throw new SizeMismatchException(head.contentLength(), total);
            }
            return new ProbeOutcome(true, total);
        }

        if (probe.status() != 206) {
            throw new HttpStatusException(probe.status());
        }

        validateContentRange(probe.contentRangeHeader(), firstRange, head.contentLength());

        if (probe.bytesWritten() != firstRange.length()) {
            throw new IOException("probe chunk truncated: got " + probe.bytesWritten()
                    + " of " + firstRange.length() + " bytes");
        }

        if (manifest != null) {
            manifest.markComplete(0);
            manifest.writeAtomically(asm.manifestFile());
        }
        return new ProbeOutcome(false, firstRange.length());
    }

    private record ResumeContext(Manifest manifest, String ifRange) {}

    private record ProbeOutcome(boolean fullDownload, long bytesWritten) {}

    private void downloadChunksParallel(URI uri, List<ByteRange> allRanges,
                                        FileAssembler asm, CancelToken cancel,
                                        RetryPolicy retry, long expectedTotal,
                                        Manifest manifest, String ifRange,
                                        ProgressDispatcher progress)
            throws IOException, InterruptedException {

        Semaphore sem = new Semaphore(options.parallelism());
        List<Callable<Void>> tasks = new ArrayList<>(allRanges.size() - 1);

        for (int i = 1; i < allRanges.size(); i++) {
            final int idx = i;
            final ByteRange range = allRanges.get(i);
            if (manifest != null && manifest.isCompleted(idx)) continue;
            tasks.add(() -> {
                sem.acquire();
                try {
                    HttpAdapter.GetResponse resp =
                            downloadChunk(idx, range, uri, asm, cancel, retry, ifRange, progress);
                    if (resp.ifRangeMismatch()) {
                        throw new ResourceChangedException(
                                "If-Range validator mismatch on chunk " + idx);
                    }
                    if (resp.status() != 206) {
                        throw new HttpStatusException(resp.status());
                    }
                    validateContentRange(resp.contentRangeHeader(), range, expectedTotal);
                    if (resp.bytesWritten() != range.length()) {
                        throw new IOException("chunk " + idx + " truncated: got "
                                + resp.bytesWritten() + " of " + range.length() + " bytes");
                    }
                    if (manifest != null) {
                        synchronized (manifest) {
                            manifest.markComplete(idx);
                            manifest.writeAtomically(asm.manifestFile());
                        }
                    }
                    return null;
                } finally {
                    sem.release();
                }
            });
        }

        if (tasks.isEmpty()) return;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            collectFutures(futures);
        }
    }

    private void collectFutures(List<Future<Void>> futures)
            throws IOException, InterruptedException {
        IOException firstError = null;
        boolean interrupted = false;

        for (int i = 0; i < futures.size(); i++) {
            Future<Void> f = futures.get(i);
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
                                                long startNanos, ProgressDispatcher progress)
            throws IOException, InterruptedException {

        RetryPolicy retry = new RetryPolicy(options);
        HttpAdapter.GetResponse resp =
                downloadChunk(0, null, uri, asm, cancel, retry, null, progress);

        if (resp.status() != 200 && resp.status() != 206) {
            throw new HttpStatusException(resp.status());
        }

        Optional<byte[]> sha = verifyAndCommit(asm);
        return success(dest, resp.bytesWritten(), startNanos, 1, sha);
    }

    /**
     * Downloads one chunk with retry. A null range means "no Range header" (single-stream).
     * On a successful body transfer (200 or 206 outside the retryable set) emits a
     * {@link ProgressEvent.ChunkCompleted} with the actual attempts and elapsed duration.
     */
    private HttpAdapter.GetResponse downloadChunk(int chunkIndex, ByteRange range,
                                                   URI uri, FileAssembler asm,
                                                   CancelToken cancel, RetryPolicy retry,
                                                   String ifRange, ProgressDispatcher progress)
            throws IOException, InterruptedException {

        long startNanos = System.nanoTime();

        for (int attempt = 0; ; attempt++) {
            if (cancel.isCancelled()) throw new InterruptedException("cancelled before chunk " + chunkIndex);

            ChunkSink sink = asm.sinkAt(range == null ? 0 : range.offset());

            try {
                HttpAdapter.GetResponse resp = http.get(uri, range, ifRange, sink, cancel);

                if (isRetryableStatus(resp.status())) {
                    Duration retryAfter = resp.retryAfter().orElse(Duration.ZERO);
                    Optional<Duration> delay = retry.evaluate(attempt,
                            new RetryPolicy.Trigger.HttpStatus(resp.status(), retryAfter));
                    if (delay.isEmpty()) throw new HttpStatusException(resp.status());
                    Thread.sleep(delay.get().toMillis());
                    continue;
                }

                if (resp.status() == 200 || resp.status() == 206) {
                    long offset = range == null ? 0L : range.offset();
                    long length = resp.bytesWritten();
                    Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
                    progress.emit(new ProgressEvent.ChunkCompleted(
                            chunkIndex, offset, length, attempt + 1, duration));
                }

                return resp;

            } catch (InterruptedException e) {
                throw e; // cancellation, do not retry
            } catch (HttpTimeoutException e) {
                Optional<Duration> delay = retry.evaluate(attempt,
                        new RetryPolicy.Trigger.Timeout());
                if (delay.isEmpty()) throw e;
                Thread.sleep(delay.get().toMillis());
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
     * A present but mismatched header always fails, silent offset corruption is not acceptable.
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

    // ── integrity verification ───────────────────────────────────────────────

    private Optional<byte[]> verifyAndCommit(FileAssembler asm) throws IOException {
        Optional<byte[]> computed = Optional.empty();
        ExpectedDigest expected = options.expectedDigest();
        if (expected != null) {
            byte[] actual = computeDigest(asm.tempFile(), expected.algorithm());
            computed = Optional.of(actual);
            if (!Arrays.equals(actual, expected.bytes())) {
                throw new IntegrityException(expected.bytes(), actual);
            }
        }
        asm.commit();
        return computed;
    }

    private static byte[] computeDigest(Path file, Algorithm algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm.javaName());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("digest algorithm not available: " + algorithm, e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return md.digest();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DownloadResult.Success success(Path dest, long bytes, long startNanos,
                                                  int chunks, Optional<byte[]> sha256) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return new DownloadResult.Success(dest, bytes, elapsed, chunks, sha256);
    }

    private static DownloadResult.Failure failure(DownloadError error, Throwable cause) {
        return new DownloadResult.Failure(error, cause);
    }

    // ── package-private exceptions ───────────────────────────────────────────

    static final class SizeMismatchException extends IOException {
        SizeMismatchException(long expected, long actual) {
            super("size mismatch: expected " + expected + ", got " + actual);
        }
    }

    static final class IntegrityException extends IOException {
        IntegrityException(byte[] expected, byte[] actual) {
            super("integrity check failed: expected SHA-256 "
                    + HexFormat.of().formatHex(expected)
                    + ", got " + HexFormat.of().formatHex(actual));
        }
    }

    static final class ResourceChangedException extends IOException {
        ResourceChangedException(String msg) { super(msg); }
    }
}
