package com.example.downloader;

import com.example.downloader.chaos.ChaosHttpAdapter;
import com.example.downloader.chaos.Fault;
import com.example.downloader.chaos.FaultDistribution;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that chaos and resume compose: a download whose first run
 * fails under high-fault conditions can be retried with --resume against an
 * adapter that has stopped faulting, and the result is correct bytes.
 *
 * Both runs use the same body and ETag (ChaosHttpAdapter's fixed
 * "chaos-etag"), so the manifest validates between runs.
 */
@Tag("chaos")
class ChaosResumeTest {

    private static final URI URI_ = URI.create("http://chaos.example.com/file");
    private static final int BODY_SIZE = 64 * 1024;
    private static final long CHUNK_SIZE = 8 * 1024L;

    @TempDir Path tmp;

    @Test
    void chaosFailureThenResumeSucceeds() throws Exception {
        byte[] body = randomBytes(BODY_SIZE, 7L);
        byte[] expectedHash = sha256(body);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(CHUNK_SIZE)
                .parallelism(4)
                .maxRetriesPerChunk(0) // make run-1 failures near-certain
                .retryBaseDelay(Duration.ZERO)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
                .build();

        // Run 1: chaos with the standard fault distribution, no retries.
        // Iterate seeds until one fails (statistically immediate at this fault rate).
        long failingSeed = -1;
        for (long seed = 0; seed < 30; seed++) {
            ChaosHttpAdapter chaos = new ChaosHttpAdapter(body, FaultDistribution.standard(), seed);
            try (Downloader dl = new Downloader(opts, chaos)) {
                DownloadResult r = dl.download(URI_, dest);
                if (r instanceof DownloadResult.Failure) {
                    failingSeed = seed;
                    break;
                }
            }
            // Lucky pass-through run — clean up and try the next seed.
            Files.deleteIfExists(dest);
            Files.deleteIfExists(Manifest.pathFor(dest));
            Files.deleteIfExists(dest.resolveSibling(dest.getFileName() + ".part"));
        }

        assertThat(failingSeed)
                .withFailMessage("could not provoke a failure within 30 chaos seeds")
                .isGreaterThanOrEqualTo(0L);
        assertThat(Manifest.pathFor(dest)).exists();
        assertThat(dest).doesNotExist();

        // Run 2: clean adapter (all pass-through), same body, same ETag.
        FaultDistribution noFault = FaultDistribution.builder()
                .weight(Fault.PASS_THROUGH, 1.0)
                .build();
        ChaosHttpAdapter clean = new ChaosHttpAdapter(body, noFault, 0L);
        try (Downloader dl = new Downloader(opts, clean)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r)
                    .withFailMessage("resume failed after chaos seed=%d", failingSeed)
                    .isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.size(dest)).isEqualTo(BODY_SIZE);
        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(expectedHash);
        assertThat(Manifest.pathFor(dest)).doesNotExist();
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] body = new byte[size];
        new Random(seed).nextBytes(body);
        return body;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
