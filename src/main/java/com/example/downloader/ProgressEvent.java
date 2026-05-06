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

    record Started(long totalBytes, int chunkCount) implements ProgressEvent {}

    record ChunkCompleted(int index, long offset, long length,
                          int attempts, Duration duration) implements ProgressEvent {}

    record Failed(DownloadError error, Throwable cause) implements ProgressEvent {}

    record Finished(long bytes, Duration elapsed, int chunks) implements ProgressEvent {}
}
