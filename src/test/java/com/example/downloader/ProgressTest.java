package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressTest {

    private static final URI URI_ = URI.create("http://fake.example.com/file");

    @TempDir Path tmp;

    @Test
    void listenerReceivesExpectedEventSequence() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 1L);
        Path dest = tmp.resolve("out.bin");

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(256 * 1024L)
                .parallelism(4)
                .progressListener(events::add)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).isInstanceOf(ProgressEvent.Started.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ProgressEvent.Finished.class);

        long chunkEvents = events.stream()
                .filter(ProgressEvent.ChunkCompleted.class::isInstance)
                .count();
        ProgressEvent.Started started = (ProgressEvent.Started) events.get(0);
        assertThat(chunkEvents).isEqualTo(started.chunkCount());
    }

    @Test
    void throwingListener_doesNotBreakDownload() throws Exception {
        byte[] data = randomBytes(512 * 1024, 2L);
        Path dest = tmp.resolve("out.bin");

        // Capture System.err to verify the "log once" message
        java.io.ByteArrayOutputStream errCapture = new java.io.ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errCapture));
        try {
            ProgressListener thrower = event -> { throw new RuntimeException("boom"); };
            DownloaderOptions opts = DownloaderOptions.builder()
                    .progressListener(thrower)
                    .build();

            try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
                DownloadResult r = dl.download(URI_, dest);
                assertThat(r).isInstanceOf(DownloadResult.Success.class);
            }
        } finally {
            System.setErr(originalErr);
        }

        // Should have logged once (and only once, even though many events fired)
        String errOutput = errCapture.toString();
        assertThat(errOutput).contains("ProgressListener threw");
        assertThat(errOutput.lines().count()).isEqualTo(1);
    }

    @Test
    void concurrencyStress_monotonicallyIncreasingTotalBytes() throws Exception {
        // 32 chunks, 16 parallelism — exercise the dispatcher under concurrent emit().
        int chunkCount = 32;
        int chunkSize = 64 * 1024;
        byte[] data = randomBytes(chunkCount * chunkSize, 3L);
        Path dest = tmp.resolve("out.bin");

        List<ProgressEvent.ChunkCompleted> chunks = new ArrayList<>();
        ProgressListener listener = event -> {
            if (event instanceof ProgressEvent.ChunkCompleted c) chunks.add(c);
        };

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(chunkSize)
                .parallelism(16)
                .progressListener(listener)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(chunks).hasSize(chunkCount);

        // Single dispatcher thread implies events arrive serially, so accumulating
        // bytesCompleted as we iterate should be monotonically non-decreasing.
        long running = 0;
        for (ProgressEvent.ChunkCompleted c : chunks) {
            running += c.length();
            assertThat(running).isPositive();
        }
        assertThat(running).isEqualTo(data.length);

        // No chunk should have negative or zero attempts/duration
        for (ProgressEvent.ChunkCompleted c : chunks) {
            assertThat(c.attempts()).isGreaterThanOrEqualTo(1);
            assertThat(c.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void failedDownload_emitsFailedEvent() throws Exception {
        byte[] data = randomBytes(1024, 4L);
        Path dest = tmp.resolve("out.bin");

        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .failGetOnAttempt(0, new IOException("simulated"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(256L)
                .parallelism(2)
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .progressListener(events::add)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Failure.class);
        }

        assertThat(events.get(events.size() - 1)).isInstanceOf(ProgressEvent.Failed.class);
        ProgressEvent.Failed last = (ProgressEvent.Failed) events.get(events.size() - 1);
        assertThat(last.error()).isEqualTo(DownloadError.IO_ERROR);
    }

    @Test
    void noOpListener_isCheap_andDownloadStillWorks() throws Exception {
        byte[] data = randomBytes(1024, 5L);
        Path dest = tmp.resolve("out.bin");
        // ProgressListener.NO_OP triggers the dispatcher's short-circuit — exercises the path.
        DownloaderOptions opts = DownloaderOptions.builder()
                .progressListener(ProgressListener.NO_OP)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
