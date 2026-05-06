package com.example.downloader;

import java.time.Duration;

/**
 * Sealed event hierarchy delivered to {@link ProgressListener}. The downloader
 * invokes the listener from a single virtual thread; implementations therefore
 * do not need to be thread-safe. The expected event sequence on a successful
 * download is {@code Started, ChunkCompleted*, Finished}; on failure
 * {@code (Started)?, ChunkCompleted*, Failed} (Started is skipped only when
 * the failure happens before HEAD succeeds).
 */
public sealed interface ProgressEvent {

    /**
     * Emitted once after a successful HEAD, before any chunk GETs. For a
     * server that did not return a {@code Content-Length}, {@code totalBytes}
     * may be {@code -1}.
     *
     * @param totalBytes  total bytes to download, or {@code -1} if unknown
     * @param chunkCount  number of chunks the planner produced
     */
    record Started(long totalBytes, int chunkCount) implements ProgressEvent {}

    /**
     * Emitted after each chunk's body has been written and protocol-level
     * checks passed. Reports the actual GET attempt count and elapsed time
     * for that chunk (including any retries).
     *
     * @param index    chunk index, zero-based
     * @param offset   byte offset in the destination file
     * @param length   bytes written for this chunk
     * @param attempts attempts made (1 = succeeded on the first try)
     * @param duration wall-clock time spent on this chunk
     */
    record ChunkCompleted(int index, long offset, long length,
                          int attempts, Duration duration) implements ProgressEvent {}

    /**
     * Terminal event for a failed download. The download has already aborted
     * and (in FRESH mode) cleaned up artifacts by the time this fires.
     *
     * @param error the typed error category
     * @param cause the underlying exception, or null if none
     */
    record Failed(DownloadError error, Throwable cause) implements ProgressEvent {}

    /**
     * Terminal event for a successful download. Mirrors the headline fields
     * of {@link DownloadResult.Success}.
     *
     * @param bytes   bytes written to the destination
     * @param elapsed total wall-clock time
     * @param chunks  chunk count
     */
    record Finished(long bytes, Duration elapsed, int chunks) implements ProgressEvent {}
}
