package com.example.downloader;

import java.time.Duration;

public record DownloaderOptions(
        long chunkSize,
        int parallelism,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetriesPerChunk,
        Duration retryBaseDelay,
        String userAgent,
        ExpectedDigest expectedDigest,
        ResumeStrategy resumeStrategy,
        ProgressListener progressListener
) {
    public DownloaderOptions {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0, got: " + chunkSize);
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be > 0, got: " + parallelism);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero())
            throw new IllegalArgumentException("connectTimeout must be positive");
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("requestTimeout must be positive");
        if (maxRetriesPerChunk < 0)
            throw new IllegalArgumentException("maxRetriesPerChunk must be >= 0, got: " + maxRetriesPerChunk);
        if (retryBaseDelay == null || retryBaseDelay.isNegative())
            throw new IllegalArgumentException("retryBaseDelay must be non-negative");
        if (userAgent == null || userAgent.isBlank())
            throw new IllegalArgumentException("userAgent must not be blank");
        if (resumeStrategy == null) throw new IllegalArgumentException("resumeStrategy must not be null");
        if (progressListener == null) throw new IllegalArgumentException("progressListener must not be null");
        // expectedDigest may be null (no integrity check requested)
    }

    public static DownloaderOptions defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long chunkSize = 8L * 1024 * 1024;
        private int parallelism = 8;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int maxRetriesPerChunk = 3;
        private Duration retryBaseDelay = Duration.ofMillis(200);
        private String userAgent = "parallel-downloader/1.0 (+java.net.http)";
        private ExpectedDigest expectedDigest = null;
        private ResumeStrategy resumeStrategy = ResumeStrategy.FRESH;
        private ProgressListener progressListener = ProgressListener.NO_OP;

        public Builder chunkSize(long v)              { this.chunkSize = v; return this; }
        public Builder parallelism(int v)             { this.parallelism = v; return this; }
        public Builder connectTimeout(Duration v)     { this.connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v)     { this.requestTimeout = v; return this; }
        public Builder maxRetriesPerChunk(int v)      { this.maxRetriesPerChunk = v; return this; }
        public Builder retryBaseDelay(Duration v)     { this.retryBaseDelay = v; return this; }
        public Builder userAgent(String v)            { this.userAgent = v; return this; }
        public Builder resumeStrategy(ResumeStrategy v) { this.resumeStrategy = v; return this; }
        public Builder progressListener(ProgressListener v) { this.progressListener = v; return this; }

        public Builder expectedDigest(Algorithm algorithm, byte[] bytes) {
            this.expectedDigest = new ExpectedDigest(algorithm, bytes);
            return this;
        }

        public DownloaderOptions build() {
            return new DownloaderOptions(chunkSize, parallelism, connectTimeout,
                    requestTimeout, maxRetriesPerChunk, retryBaseDelay, userAgent,
                    expectedDigest, resumeStrategy, progressListener);
        }
    }
}
