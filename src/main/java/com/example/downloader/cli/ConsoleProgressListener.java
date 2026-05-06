package com.example.downloader.cli;

import com.example.downloader.ProgressEvent;
import com.example.downloader.ProgressListener;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Live progress for the CLI. Renders one line per ChunkCompleted event:
 *   <bytes>/<total> @ <MiB/s> ETA <h:mm:ss> (<n>/<N> chunks)
 *
 * In a TTY the line is overwritten with '\r'; in a non-TTY each update is on
 * its own line. On Finished or Failed a trailing newline closes the rendered
 * line so subsequent output starts cleanly.
 */
final class ConsoleProgressListener implements ProgressListener {

    private final PrintStream out;
    private final boolean tty;

    private long totalBytes = -1L;
    private int chunkCount = 0;
    private long bytesCompleted = 0L;
    private int chunksCompleted = 0;
    private long startNanos;

    ConsoleProgressListener(PrintStream out, boolean tty) {
        this.out = out;
        this.tty = tty;
    }

    @Override
    public void onProgress(ProgressEvent event) {
        switch (event) {
            case ProgressEvent.Started s -> {
                this.totalBytes = s.totalBytes();
                this.chunkCount = s.chunkCount();
                this.startNanos = System.nanoTime();
            }
            case ProgressEvent.ChunkCompleted c -> {
                this.bytesCompleted += c.length();
                this.chunksCompleted++;
                renderLine();
            }
            case ProgressEvent.Finished ignored -> {
                if (tty && chunksCompleted > 0) out.println();
            }
            case ProgressEvent.Failed ignored -> {
                if (tty && chunksCompleted > 0) out.println();
            }
        }
    }

    private void renderLine() {
        long elapsedNs = System.nanoTime() - startNanos;
        double elapsedSec = elapsedNs / 1e9;
        double mibPerSec = elapsedSec > 0
                ? bytesCompleted / elapsedSec / (1024.0 * 1024.0) : 0.0;
        long remaining = totalBytes > 0 ? totalBytes - bytesCompleted : -1;
        Duration eta = (remaining > 0 && mibPerSec > 0)
                ? Duration.ofNanos((long) (remaining / mibPerSec / (1024.0 * 1024.0) * 1e9))
                : null;

        StringBuilder sb = new StringBuilder();
        sb.append(formatBytes(bytesCompleted));
        if (totalBytes > 0) sb.append('/').append(formatBytes(totalBytes));
        sb.append(String.format(" @ %.2f MiB/s", mibPerSec));
        if (eta != null) sb.append(" ETA ").append(formatEta(eta));
        sb.append(String.format(" (%d/%d chunks)", chunksCompleted, chunkCount));

        if (tty) {
            out.print('\r');
            out.print(sb);
            out.print("   "); // pad to clear trailing residue from a longer prior line
            out.flush();
        } else {
            out.println(sb);
        }
    }

    private static String formatBytes(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KiB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MiB", b / (1024.0 * 1024));
        return String.format("%.2f GiB", b / (1024.0 * 1024 * 1024));
    }

    private static String formatEta(Duration d) {
        long total = d.getSeconds();
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }
}
