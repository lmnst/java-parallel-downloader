package com.example.downloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RangePlanner {

    private RangePlanner() {}

    public static List<ByteRange> plan(long totalBytes, long chunkSize) {
        if (totalBytes < 0) throw new IllegalArgumentException("totalBytes must be >= 0, got: " + totalBytes);
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0, got: " + chunkSize);
        if (totalBytes == 0) return Collections.emptyList();

        List<ByteRange> ranges = new ArrayList<>();
        long offset = 0;
        while (offset < totalBytes) {
            long length = Math.min(chunkSize, totalBytes - offset);
            ranges.add(new ByteRange(offset, length));
            offset += length;
        }
        return Collections.unmodifiableList(ranges);
    }
}
