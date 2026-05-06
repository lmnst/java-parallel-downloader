package com.example.downloader.cli;

import com.example.downloader.Algorithm;
import com.example.downloader.Downloader;
import com.example.downloader.DownloaderOptions;
import com.example.downloader.DownloadResult;
import com.example.downloader.HttpStatusException;
import com.example.downloader.ProgressEvent;
import com.example.downloader.ProgressListener;
import com.example.downloader.ResumeStrategy;

import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;

public final class Main {

    static final int EXIT_SUCCESS          = 0;
    static final int EXIT_GENERIC          = 1;
    static final int EXIT_USAGE            = 2;
    static final int EXIT_TRANSIENT        = 3;
    static final int EXIT_INTEGRITY        = 4;
    static final int EXIT_CANCELLED        = 5;
    static final int EXIT_RESOURCE_CHANGED = 6;

    private static final String USAGE = """
            Usage: downloader --url <uri> --out <path> [options]

            Required:
              --url <uri>           Source URL to download.
              --out <path>          Destination file path.

            Options:
              --chunk-size <size>   Chunk size; bare number = bytes; suffixes K, M, G, KiB, MiB, GiB
                                    are 1024-based (e.g. 8M = 8MiB = 8388608). Default: 8 MiB.
              --parallelism <int>   Maximum concurrent ranged GETs. Default: 8.
              --sha256 <hex>        Verify the download against this SHA-256 (64 hex chars).
                                    Mismatch fails with exit 4; destination is not written.
              --resume              Resume an interrupted download. Re-uses an existing
                                    .part / .part.json sidecar if its recorded ETag,
                                    contentLength, and chunkSize all still match the
                                    server's HEAD response; mismatch fails with exit 6.
              --report <text|json>  Output format. Default: text.
              -h, --help            Show this help and exit.

            Exit codes:
              0  success
              1  generic failure (e.g. HTTP 4xx, ranges-not-supported)
              2  usage / argument error
              3  transient / network failure (I/O, timeout, HTTP 5xx)
              4  integrity failure (size mismatch)
              5  cancelled
              6  resource changed
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return EXIT_USAGE;
        }

        if (parsed.help) {
            out.print(USAGE);
            return EXIT_SUCCESS;
        }

        // Pick a progress listener based on the report mode:
        //   text → ConsoleProgressListener (live `\r`-overwriting status line)
        //   json → JsonAccumulator (silent; per-chunk details fed into final JSON)
        boolean tty = System.console() != null;
        JsonAccumulator jsonAccum = parsed.report == Report.JSON ? new JsonAccumulator() : null;
        ProgressListener listener = jsonAccum != null
                ? jsonAccum
                : new ConsoleProgressListener(out, tty);

        DownloaderOptions opts = buildOptions(parsed, listener);

        try (Downloader downloader = new Downloader(opts)) {
            DownloadResult result = downloader.download(parsed.url, parsed.out);
            return report(result, parsed.report, jsonAccum, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return EXIT_CANCELLED;
        }
    }

    // ── argument parsing ─────────────────────────────────────────────────────

    private static final class Args {
        URI url;
        Path out;
        Long chunkSize;
        Integer parallelism;
        byte[] sha256;
        boolean resume;
        Report report = Report.TEXT;
        boolean help;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                switch (s) {
                    case "--help", "-h"  -> a.help = true;
                    case "--url"         -> a.url = parseUri(value(argv, ++i, s));
                    case "--out"         -> a.out = Path.of(value(argv, ++i, s));
                    case "--chunk-size"  -> a.chunkSize = parseSize(value(argv, ++i, s));
                    case "--parallelism" -> a.parallelism = parsePositiveInt(value(argv, ++i, s));
                    case "--sha256"      -> a.sha256 = parseHex(value(argv, ++i, s), 32);
                    case "--resume"      -> a.resume = true;
                    case "--report"      -> a.report = parseReport(value(argv, ++i, s));
                    default              -> throw new UsageException("unknown flag: " + s);
                }
            }
            if (!a.help) {
                if (a.url == null) throw new UsageException("missing required flag: --url");
                if (a.out == null) throw new UsageException("missing required flag: --out");
            }
            return a;
        }

        private static String value(String[] argv, int idx, String flag) {
            if (idx >= argv.length) throw new UsageException(flag + " requires a value");
            return argv[idx];
        }
    }

    enum Report { TEXT, JSON }

    private static URI parseUri(String s) {
        try {
            URI u = new URI(s);
            if (u.getScheme() == null) throw new UsageException("--url must include scheme: " + s);
            return u;
        } catch (URISyntaxException e) {
            throw new UsageException("invalid --url: " + s);
        }
    }

    private static int parsePositiveInt(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n <= 0) throw new UsageException("must be > 0: " + s);
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("invalid integer: " + s);
        }
    }

    private static Report parseReport(String s) {
        return switch (s.toLowerCase()) {
            case "text" -> Report.TEXT;
            case "json" -> Report.JSON;
            default     -> throw new UsageException("--report must be 'text' or 'json', got: " + s);
        };
    }

    private static byte[] parseHex(String s, int expectedLen) {
        String trimmed = s.trim();
        if (trimmed.length() != expectedLen * 2) {
            throw new UsageException("--sha256 must be " + (expectedLen * 2)
                    + " hex chars, got " + trimmed.length());
        }
        try {
            return HexFormat.of().parseHex(trimmed);
        } catch (IllegalArgumentException e) {
            throw new UsageException("--sha256 must be valid hex: " + s);
        }
    }

    static long parseSize(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) throw new UsageException("--chunk-size empty");

        long mult = 1L;
        String num = s;

        // Suffixes are 1024-based for both IEC (KiB/MiB/GiB) and bare (K/M/G).
        // IEC checked first so "MiB" doesn't masquerade as "M".
        String[][] suffixes = {
                {"GiB", String.valueOf(1L << 30)},
                {"MiB", String.valueOf(1L << 20)},
                {"KiB", String.valueOf(1L << 10)},
                {"G",   String.valueOf(1L << 30)},
                {"M",   String.valueOf(1L << 20)},
                {"K",   String.valueOf(1L << 10)},
        };
        for (String[] pair : suffixes) {
            if (s.endsWith(pair[0])) {
                mult = Long.parseLong(pair[1]);
                num = s.substring(0, s.length() - pair[0].length()).trim();
                break;
            }
        }

        long base;
        try {
            base = Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw new UsageException("invalid --chunk-size: " + raw);
        }
        if (base <= 0) throw new UsageException("--chunk-size must be > 0: " + raw);
        if (mult > 1 && base > Long.MAX_VALUE / mult) {
            throw new UsageException("--chunk-size overflows: " + raw);
        }
        return base * mult;
    }

    // ── options + reporting ──────────────────────────────────────────────────

    private static DownloaderOptions buildOptions(Args a, ProgressListener listener) {
        DownloaderOptions.Builder b = DownloaderOptions.builder();
        if (a.chunkSize != null) b.chunkSize(a.chunkSize);
        if (a.parallelism != null) b.parallelism(a.parallelism);
        if (a.sha256 != null) b.expectedDigest(Algorithm.SHA_256, a.sha256);
        if (a.resume) b.resumeStrategy(ResumeStrategy.RESUME_IF_VALID);
        b.progressListener(listener);
        return b.build();
    }

    private static int report(DownloadResult result, Report fmt, JsonAccumulator chunks,
                              PrintStream out, PrintStream err) {
        return switch (result) {
            case DownloadResult.Success s -> {
                if (fmt == Report.JSON) {
                    out.println(jsonSuccess(s, chunks));
                } else {
                    out.printf("Downloaded %d bytes in %s (%d chunks) -> %s%n",
                            s.bytes(), formatDuration(s.elapsed()), s.chunks(), s.file());
                }
                yield EXIT_SUCCESS;
            }
            case DownloadResult.Failure f -> {
                int code = exitCodeFor(f);
                if (fmt == Report.JSON) {
                    out.println(jsonFailure(f, code, chunks));
                } else {
                    err.println("download failed: " + f.error()
                            + (f.cause() != null ? " - " + f.cause().getMessage() : ""));
                }
                yield code;
            }
        };
    }

    private static String jsonSuccess(DownloadResult.Success s, JsonAccumulator chunks) {
        StringBuilder j = new StringBuilder()
                .append("{\"status\":\"success\"")
                .append(",\"file\":\"").append(jsonEscape(s.file().toString())).append('"')
                .append(",\"bytes\":").append(s.bytes())
                .append(",\"elapsedMs\":").append(s.elapsed().toMillis())
                .append(",\"chunks\":").append(s.chunks());
        s.sha256().ifPresent(h -> j
                .append(",\"sha256\":\"").append(HexFormat.of().formatHex(h)).append('"'));
        appendChunkDetails(j, chunks);
        return j.append('}').toString();
    }

    private static String jsonFailure(DownloadResult.Failure f, int exitCode, JsonAccumulator chunks) {
        String causeMsg = f.cause() == null ? "" : nullSafe(f.cause().getMessage());
        StringBuilder j = new StringBuilder()
                .append("{\"status\":\"failure\"")
                .append(",\"error\":\"").append(f.error()).append('"')
                .append(",\"exitCode\":").append(exitCode)
                .append(",\"cause\":\"").append(jsonEscape(causeMsg)).append('"');
        appendChunkDetails(j, chunks);
        return j.append('}').toString();
    }

    private static void appendChunkDetails(StringBuilder j, JsonAccumulator chunks) {
        if (chunks == null || chunks.chunks.isEmpty()) return;
        j.append(",\"chunkDetails\":[");
        for (int i = 0; i < chunks.chunks.size(); i++) {
            if (i > 0) j.append(',');
            ProgressEvent.ChunkCompleted c = chunks.chunks.get(i);
            j.append("{\"index\":").append(c.index())
             .append(",\"offset\":").append(c.offset())
             .append(",\"length\":").append(c.length())
             .append(",\"attempts\":").append(c.attempts())
             .append(",\"durationMs\":").append(c.duration().toMillis())
             .append('}');
        }
        j.append(']');
    }

    private static int exitCodeFor(DownloadResult.Failure f) {
        return switch (f.error()) {
            case HTTP_ERROR -> {
                if (f.cause() instanceof HttpStatusException hse) {
                    yield hse.statusCode() >= 500 ? EXIT_TRANSIENT : EXIT_GENERIC;
                }
                yield EXIT_GENERIC;
            }
            case IO_ERROR, TIMEOUT                -> EXIT_TRANSIENT;
            case SIZE_MISMATCH, INTEGRITY_FAILURE -> EXIT_INTEGRITY;
            case CANCELLED                        -> EXIT_CANCELLED;
            case RESOURCE_CHANGED                 -> EXIT_RESOURCE_CHANGED;
            case RANGES_NOT_SUPPORTED             -> EXIT_GENERIC;
        };
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + " ms";
        return String.format("%.2f s", ms / 1000.0);
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String m) { super(m); }
    }

    private Main() {}
}
