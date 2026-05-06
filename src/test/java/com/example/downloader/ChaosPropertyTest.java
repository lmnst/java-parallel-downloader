package com.example.downloader;

import com.example.downloader.chaos.ChaosHttpAdapter;
import com.example.downloader.chaos.FaultDistribution;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: for any seed in [0, 120), the downloader against a chaos
 * adapter ends up in exactly one of two states:
 *
 *   1. Success — the destination file SHA-256 matches the source corpus.
 *   2. Failure — the result carries a typed DownloadError, the destination
 *      does not exist, and no .part / .part.json files linger.
 *
 * No third state is acceptable: a Success with corrupted bytes, or a Failure
 * with leftover artifacts, is a real bug. When this test fails the seed is
 * printed so the run can be replayed deterministically.
 */
@Tag("chaos")
class ChaosPropertyTest {

    private static final URI URI_ = URI.create("http://chaos.example.com/file");
    private static final int BODY_SIZE = 64 * 1024;     // 64 KiB
    private static final long CHUNK_SIZE = 8 * 1024L;   // 8 KiB → 8 chunks
    private static final int PARALLELISM = 4;

    @TempDir Path tmp;

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {
              0,   1,   2,   3,   4,   5,   6,   7,   8,   9,
             10,  11,  12,  13,  14,  15,  16,  17,  18,  19,
             20,  21,  22,  23,  24,  25,  26,  27,  28,  29,
             30,  31,  32,  33,  34,  35,  36,  37,  38,  39,
             40,  41,  42,  43,  44,  45,  46,  47,  48,  49,
             50,  51,  52,  53,  54,  55,  56,  57,  58,  59,
             60,  61,  62,  63,  64,  65,  66,  67,  68,  69,
             70,  71,  72,  73,  74,  75,  76,  77,  78,  79,
             80,  81,  82,  83,  84,  85,  86,  87,  88,  89,
             90,  91,  92,  93,  94,  95,  96,  97,  98,  99,
            100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
            110, 111, 112, 113, 114, 115, 116, 117, 118, 119
    })
    void invariantHolds(long seed) throws Exception {
        byte[] body = makeBody(seed);
        byte[] expectedHash = sha256(body);
        Path dest = tmp.resolve("dest.bin");

        ChaosHttpAdapter adapter = new ChaosHttpAdapter(body, FaultDistribution.standard(), seed);
        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(CHUNK_SIZE)
                .parallelism(PARALLELISM)
                .maxRetriesPerChunk(3)
                .retryBaseDelay(Duration.ZERO)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();

        DownloadResult result;
        try (Downloader dl = new Downloader(opts, adapter)) {
            result = dl.download(URI_, dest);
        }

        if (result instanceof DownloadResult.Success s) {
            assertThat(Files.size(dest))
                    .withFailMessage("seed=%d: success but size mismatch", seed)
                    .isEqualTo(BODY_SIZE);
            byte[] actualHash = sha256(Files.readAllBytes(dest));
            assertThat(actualHash)
                    .withFailMessage("seed=%d: success but bytes corrupted", seed)
                    .isEqualTo(expectedHash);
            assertThat(s.bytes()).isEqualTo(BODY_SIZE);
        } else if (result instanceof DownloadResult.Failure f) {
            assertThat(f.error())
                    .withFailMessage("seed=%d: untyped failure", seed)
                    .isNotNull();
            assertThat(dest)
                    .withFailMessage("seed=%d: destination exists after failure", seed)
                    .doesNotExist();
            assertThat(noPartArtifacts(dest))
                    .withFailMessage("seed=%d: .part / .part.json artifact lingered", seed)
                    .isTrue();
        }
    }

    private static byte[] makeBody(long seed) {
        byte[] body = new byte[BODY_SIZE];
        new Random(seed).nextBytes(body);
        return body;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private boolean noPartArtifacts(Path dest) throws IOException {
        try (var s = Files.list(tmp)) {
            return s.noneMatch(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".part") || n.endsWith(".part.json")
                        || n.endsWith(".part.json.tmp");
            });
        }
    }
}
