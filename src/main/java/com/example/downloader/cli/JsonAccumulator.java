package com.example.downloader.cli;

import com.example.downloader.ProgressEvent;
import com.example.downloader.ProgressListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures ProgressEvent.ChunkCompleted entries so the CLI's JSON report can
 * embed per-chunk attempts and durations alongside the final summary.
 *
 * Started supplies the announced totalBytes and chunkCount; Finished/Failed
 * are intentionally ignored here — the CLI uses the DownloadResult itself
 * for those terminal values to avoid double-bookkeeping.
 */
final class JsonAccumulator implements ProgressListener {

    long announcedTotalBytes = -1L;
    int announcedChunkCount = 0;
    final List<ProgressEvent.ChunkCompleted> chunks = new ArrayList<>();

    @Override
    public void onProgress(ProgressEvent event) {
        switch (event) {
            case ProgressEvent.Started s -> {
                announcedTotalBytes = s.totalBytes();
                announcedChunkCount = s.chunkCount();
            }
            case ProgressEvent.ChunkCompleted c -> chunks.add(c);
            case ProgressEvent.Finished ignored -> {}
            case ProgressEvent.Failed ignored -> {}
        }
    }
}
