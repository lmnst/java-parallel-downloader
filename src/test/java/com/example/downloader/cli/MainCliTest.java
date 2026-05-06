package com.example.downloader.cli;

import com.example.downloader.DownloadError;
import com.example.downloader.DownloadResult;
import com.example.downloader.HttpStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link Main}. The {@code IntegrationDockerTest} suite
 * drives the happy path against a live httpd container; this suite covers
 * argument parsing, usage errors, size-suffix handling, and the exit-code
 * mapping that the integration test does not exercise.
 */
class MainCliTest {

    @TempDir Path tmp;

    private record Result(int code, String stdout, String stderr) {}

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out), new PrintStream(err));
        return new Result(code, out.toString(), err.toString());
    }

    /** Wraps a fixed --url/--out around the flag under test so parsing fails on the flag we care about. */
    private Result runDownloadAttempt(String... extra) {
        String[] base = {"--url", "http://example.com/file",
                         "--out", tmp.resolve("x.bin").toString()};
        String[] merged = new String[base.length + extra.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(extra, 0, merged, base.length, extra.length);
        return run(merged);
    }

    private static DownloadResult.Failure failure(DownloadError e, Throwable cause) {
        return new DownloadResult.Failure(e, cause);
    }

    // ── --help / unknown / missing required ─────────────────────────────────

    @Test
    void help_succeedsAndPrintsUsage() {
        Result r = run("--help");
        assertThat(r.code).isEqualTo(0);
        assertThat(r.stdout).contains("Usage: downloader", "Required:", "Exit codes:");
    }

    @Test
    void unknownFlag_returnsUsageError() {
        Result r = run("--frobnicate", "yes");
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("unknown flag: --frobnicate");
    }

    @Test
    void missingRequiredFlag_returnsUsageError() {
        Result onlyOut = run("--out", tmp.resolve("x.bin").toString());
        assertThat(onlyOut.code).isEqualTo(2);
        assertThat(onlyOut.stderr).contains("--url");

        Result onlyUrl = run("--url", "http://example.com/file");
        assertThat(onlyUrl.code).isEqualTo(2);
        assertThat(onlyUrl.stderr).contains("--out");
    }

    @Test
    void flagWithoutValue_returnsUsageError() {
        Result r = run("--url");
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("--url requires a value");
    }

    // ── --chunk-size ────────────────────────────────────────────────────────

    @Test
    void chunkSize_MBSuffixIsRejected() {
        // "MB" (decimal-style) is intentionally not a supported suffix; only
        // K/M/G and KiB/MiB/GiB are. Keeping this assertion locks the README's
        // documented suffix list against silent drift.
        Result r = runDownloadAttempt("--chunk-size", "8MB");
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("--chunk-size");
    }

    @Test
    void chunkSize_invalidValues_areRejected() {
        assertThat(runDownloadAttempt("--chunk-size", "0").code).isEqualTo(2);
        assertThat(runDownloadAttempt("--chunk-size", "abc").code).isEqualTo(2);
        assertThat(runDownloadAttempt("--chunk-size", "").code).isEqualTo(2);
    }

    @Test
    void parseSize_validSuffixes_returnExpectedBytes() {
        assertThat(Main.parseSize("1024")).isEqualTo(1024L);
        assertThat(Main.parseSize("1K")).isEqualTo(1L << 10);
        assertThat(Main.parseSize("1KiB")).isEqualTo(1L << 10);
        assertThat(Main.parseSize("8M")).isEqualTo(8L << 20);
        assertThat(Main.parseSize("8MiB")).isEqualTo(8L << 20);
        assertThat(Main.parseSize("2G")).isEqualTo(2L << 30);
    }

    // ── --sha256 / --report / --parallelism / --url ─────────────────────────

    @Test
    void sha256_wrongLength_isRejected() {
        Result r = runDownloadAttempt("--sha256", "abcd1234");
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("--sha256", "64");
    }

    @Test
    void sha256_nonHex_isRejected() {
        Result r = runDownloadAttempt("--sha256", "z".repeat(64));
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("--sha256");
    }

    @Test
    void report_invalidValue_isRejected() {
        Result r = runDownloadAttempt("--report", "yaml");
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("--report");
    }

    @Test
    void parallelism_invalidValues_areRejected() {
        assertThat(runDownloadAttempt("--parallelism", "0").code).isEqualTo(2);
        assertThat(runDownloadAttempt("--parallelism", "lots").code).isEqualTo(2);
    }

    @Test
    void url_withoutScheme_isRejected() {
        Result r = run("--url", "example.com/file",
                       "--out", tmp.resolve("x.bin").toString());
        assertThat(r.code).isEqualTo(2);
        assertThat(r.stderr).contains("scheme");
    }

    // ── exit-code mapping (mirrors the table in --help) ─────────────────────

    @Test
    void exitCodeFor_eachDownloadError_mapsAsDocumented() {
        // HTTP errors split on status: 4xx → generic (1), 5xx → transient (3),
        // missing HttpStatusException → generic (1).
        assertThat(Main.exitCodeFor(failure(DownloadError.HTTP_ERROR, new HttpStatusException(404)))).isEqualTo(1);
        assertThat(Main.exitCodeFor(failure(DownloadError.HTTP_ERROR, new HttpStatusException(503)))).isEqualTo(3);
        assertThat(Main.exitCodeFor(failure(DownloadError.HTTP_ERROR, new IOException()))).isEqualTo(1);

        assertThat(Main.exitCodeFor(failure(DownloadError.IO_ERROR, new IOException()))).isEqualTo(3);
        assertThat(Main.exitCodeFor(failure(DownloadError.TIMEOUT, new IOException()))).isEqualTo(3);
        assertThat(Main.exitCodeFor(failure(DownloadError.SIZE_MISMATCH, null))).isEqualTo(4);
        assertThat(Main.exitCodeFor(failure(DownloadError.INTEGRITY_FAILURE, null))).isEqualTo(4);
        assertThat(Main.exitCodeFor(failure(DownloadError.CANCELLED, null))).isEqualTo(5);
        assertThat(Main.exitCodeFor(failure(DownloadError.RESOURCE_CHANGED, null))).isEqualTo(6);
        assertThat(Main.exitCodeFor(failure(DownloadError.RANGES_NOT_SUPPORTED, null))).isEqualTo(1);
    }
}
