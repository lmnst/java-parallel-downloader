package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class DownloaderUnitTest {

    private static final URI FAKE_URI = URI.create("http://fake.example.com/file");

    @TempDir Path tmp;

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private Downloader downloaderWith(HttpAdapter adapter) {
        return new Downloader(DownloaderOptions.defaults(), adapter);
    }

    private void assertNoPartFiles() throws Exception {
        try (var stream = Files.list(tmp)) {
            assertThat(stream.noneMatch(p -> p.getFileName().toString().endsWith(".part")))
                    .as("no .part file should remain in tmp dir")
                    .isTrue();
        }
    }

    // ── parallel (range-capable server) ─────────────────────────────────────

    @Test
    void parallelDownload_sha256Matches() throws Exception {
        byte[] data = randomBytes(4 * 1024 * 1024, 1L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    @Test
    void parallelDownload_reportedBytesAndChunksAreCorrect() throws Exception {
        byte[] data = randomBytes(8 * 1024 * 1024 + 100, 2L); // not an exact multiple
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(1024 * 1024L) // 1 MiB chunks → 9 chunks
                .parallelism(4)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(s.bytes()).isEqualTo(data.length);
            assertThat(s.chunks()).isEqualTo(9);
            assertThat(s.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    // ── fallback: server returns 200 instead of 206 ──────────────────────────

    @Test
    void fallbackToSingleStream_whenServerReturns200OnRangeRequest() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 3L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(true)  // HEAD says yes …
                .forceFullGet(true)  // … but GET always returns 200
                .build();

        try (Downloader dl = downloaderWith(fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
        assertNoPartFiles();
    }

    // ── A2: Content-Range mismatch ───────────────────────────────────────────

    @Test
    void serverReturns206WithMismatchedContentRange_failsTyped() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 77L);
        Path dest = tmp.resolve("out.bin");

        // Serve correct data but return "bytes 0-0/N" as Content-Range for every chunk.
        // For chunk 0 (ByteRange(0, 524288)), the validator expects end=524287 but gets end=0.
        HttpAdapter mismatchedRange = new HttpAdapter() {
            @Override
            public HeadResponse head(URI uri) {
                return new HeadResponse(200, data.length, true, "\"etag\"");
            }
            @Override
            public GetResponse get(URI uri, ByteRange range,
                    Consumer<ByteBuffer> sink, CancelToken cancel) {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(data));
                    return new GetResponse(200, data.length, null);
                }
                sink.accept(ByteBuffer.wrap(data, (int) range.offset(), (int) range.length()));
                return new GetResponse(206, range.length(), "bytes 0-0/" + data.length);
            }
        };

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(512 * 1024L)
                .parallelism(4)
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, mismatchedRange)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            // Content-Range mismatch is a protocol violation mapped to IO_ERROR
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    // ── A1: truncated 206 body ───────────────────────────────────────────────

    @Test
    void serverReturns206WithTruncatedBody_failsTyped() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 13L);
        Path dest = tmp.resolve("out.bin");

        // Returns a correct Content-Range but only half the requested bytes.
        HttpAdapter truncatedBody = new HttpAdapter() {
            @Override
            public HeadResponse head(URI uri) {
                return new HeadResponse(200, data.length, true, "\"etag\"");
            }
            @Override
            public GetResponse get(URI uri, ByteRange range,
                    Consumer<ByteBuffer> sink, CancelToken cancel) {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(data));
                    return new GetResponse(200, data.length, null);
                }
                int half = (int) range.length() / 2;
                sink.accept(ByteBuffer.wrap(data, (int) range.offset(), half));
                String cr = "bytes " + range.offset() + "-" + range.lastByte() + "/" + data.length;
                return new GetResponse(206, half, cr);
            }
        };

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(512 * 1024L)
                .parallelism(4)
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, truncatedBody)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    // ── single-stream mode (no Accept-Ranges) ────────────────────────────────

    @Test
    void singleStreamDownload_noRangeSupport() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 4L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.noRangeSupport(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    @Test
    void singleStreamDownload_parallelism1ForcesStream() throws Exception {
        byte[] data = randomBytes(512 * 1024, 5L);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder().parallelism(1).build();
        FakeHttpAdapter fake = FakeHttpAdapter.parallelCapable(data);

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── retry ────────────────────────────────────────────────────────────────

    @Test
    void retryOnIoFailure_succeedsAfterTransientError() throws Exception {
        byte[] data = randomBytes(1024, 6L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("connection reset"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(3)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void noRetry_returnsFailureAfterMaxRetries() throws Exception {
        byte[] data = randomBytes(1024, 7L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("always fails"))
                .failGetOnAttempt(1, new IOException("always fails"))
                .failGetOnAttempt(2, new IOException("always fails"))
                .failGetOnAttempt(3, new IOException("always fails"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(2)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(dest).doesNotExist();
    }

    @Test
    void chunkExhaustsRetries_leavesNoDestNoPartFile() throws Exception {
        // Finding 8: maxRetriesPerChunk(2) means attempts 0, 1, 2 all run before giving up.
        // Configure 3 failures so every attempt hits an error, confirming retries were consumed.
        byte[] data = randomBytes(1024, 71L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("transient"))
                .failGetOnAttempt(1, new IOException("transient"))
                .failGetOnAttempt(2, new IOException("transient"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(2)   // 1 initial + 2 retries = 3 total GET calls
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(fake.getCallCount()).isEqualTo(3); // confirms retries were attempted
        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    @Test
    void cancellation_leavesNoPartialFile() throws Exception {
        byte[] data = randomBytes(4 * 1024 * 1024, 8L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);
            handle.cancel();
            DownloadResult result = handle.join();
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
        }

        assertThat(dest).doesNotExist();
    }

    @Test
    void cancellation_leavesNoPartFile_resultIsCancelledOrAbsent() throws Exception {
        byte[] data = randomBytes(4 * 1024 * 1024, 88L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);
            handle.cancel();
            DownloadResult result = handle.joinWithTimeout(Duration.ofSeconds(5));
            if (result instanceof DownloadResult.Failure f) {
                assertThat(f.error()).isEqualTo(DownloadError.CANCELLED);
            }
        }

        assertNoPartFiles();
    }

    /**
     * Finding 7: deterministic cancellation test.
     * The blocking adapter signals when its GET is entered, then blocks until the
     * cancel token fires, making the cancel-before-completion outcome guaranteed.
     */
    @Test
    void cancellation_deterministic_returnsCancelledAndCleansUp() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 55L);
        Path dest = tmp.resolve("out.bin");

        CountDownLatch getEntered = new CountDownLatch(1);

        HttpAdapter blockingAdapter = new HttpAdapter() {
            @Override
            public HeadResponse head(URI uri) {
                // No Accept-Ranges → single-stream → exactly one GET call
                return new HeadResponse(200, data.length, false, null);
            }
            @Override
            public GetResponse get(URI uri, ByteRange range,
                    Consumer<ByteBuffer> sink, CancelToken cancel)
                    throws IOException, InterruptedException {
                getEntered.countDown(); // notify test that GET has started
                // Block until the cancel token fires or the thread is interrupted
                while (!cancel.isCancelled()) {
                    Thread.sleep(20);
                }
                throw new InterruptedException("cancelled");
            }
        };

        try (Downloader dl = new Downloader(DownloaderOptions.defaults(), blockingAdapter)) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);

            // Wait until the download is definitely inside GET, then cancel
            assertThat(getEntered.await(5, TimeUnit.SECONDS))
                    .as("GET must start within 5 s").isTrue();
            handle.cancel();

            DownloadResult result = handle.joinWithTimeout(Duration.ofSeconds(5));

            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.CANCELLED);
        }

        assertThat(dest).doesNotExist();

        // FutureTask.cancel(true) makes joinWithTimeout() return before the task thread
        // finishes asm.abort(). Poll up to 2 s for the temp file to be deleted.
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (var s = Files.list(tmp)) {
                if (s.noneMatch(p -> p.getFileName().toString().endsWith(".part"))) break;
            }
            Thread.sleep(10);
        }
        assertNoPartFiles();
    }

    // ── destination already exists ───────────────────────────────────────────

    @Test
    void destinationAlreadyExists_successReplacesAtomically() throws Exception {
        byte[] original = randomBytes(512, 101L);
        byte[] replacement = randomBytes(2 * 1024 * 1024, 102L);
        Path dest = tmp.resolve("out.bin");
        Files.write(dest, original);

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(replacement))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(replacement));
        assertNoPartFiles();
    }

    @Test
    void destinationAlreadyExists_failureLeavesOriginalIntact() throws Exception {
        byte[] original = randomBytes(512, 103L);
        byte[] attempted = randomBytes(1024, 104L);
        Path dest = tmp.resolve("out.bin");
        Files.write(dest, original);

        FakeHttpAdapter fake = FakeHttpAdapter.builder(attempted)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("network gone"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(original);
        assertNoPartFiles();
    }

    // ── destination is a directory ───────────────────────────────────────────

    @Test
    void destinationIsDirectory_failsClearly() throws Exception {
        byte[] data = randomBytes(1024, 99L);
        Path dest = tmp.resolve("itsadir");
        Files.createDirectory(dest);

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(dest).isDirectory(); // original directory untouched
        assertNoPartFiles();
    }

    // ── small files ──────────────────────────────────────────────────────────

    @Test
    void tinyFile_singleByte() throws Exception {
        byte[] data = new byte[]{42};
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void exactlyOneChunk() throws Exception {
        byte[] data = randomBytes(8 * 1024 * 1024, 9L); // exactly one default chunk
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            assertThat(((DownloadResult.Success) result).chunks()).isEqualTo(1);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── async ────────────────────────────────────────────────────────────────

    @Test
    void downloadAsync_returnsSuccess() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 10L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);
            DownloadResult result = handle.join();
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            assertThat(handle.state()).isEqualTo(DownloadHandle.State.DONE);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── HEAD failures ────────────────────────────────────────────────────────

    @Test
    void headReturns404_returnsHttpError() throws Exception {
        Path dest = tmp.resolve("out.bin");

        HttpAdapter failHead = new HttpAdapter() {
            @Override public HeadResponse head(java.net.URI uri) {
                return new HeadResponse(404, -1, false, null);
            }
            @Override public GetResponse get(java.net.URI uri, ByteRange range,
                    java.util.function.Consumer<java.nio.ByteBuffer> sink, CancelToken cancel) {
                throw new UnsupportedOperationException();
            }
        };

        try (Downloader dl = downloaderWith(failHead)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.HTTP_ERROR);
        }

        assertThat(dest).doesNotExist();
    }
}
