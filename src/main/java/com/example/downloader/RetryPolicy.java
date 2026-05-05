package com.example.downloader;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

final class RetryPolicy {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 429, 500, 502, 503, 504);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    sealed interface Trigger {
        record HttpStatus(int statusCode, Duration retryAfterHint) implements Trigger {
            HttpStatus(int statusCode) { this(statusCode, Duration.ZERO); }
        }
        record IoFailure(IOException cause) implements Trigger {}
        record Timeout() implements Trigger {}
    }

    private final int maxRetries;
    private final Duration baseDelay;
    private final Random random;

    RetryPolicy(DownloaderOptions options, Random random) {
        this.maxRetries = options.maxRetriesPerChunk();
        this.baseDelay = options.retryBaseDelay();
        this.random = random;
    }

    RetryPolicy(DownloaderOptions options) {
        this(options, new Random());
    }

    Optional<Duration> evaluate(int attempt, Trigger trigger) {
        if (attempt >= maxRetries) return Optional.empty();
        if (!isRetryable(trigger)) return Optional.empty();
        return Optional.of(computeDelay(attempt, trigger));
    }

    private boolean isRetryable(Trigger trigger) {
        return switch (trigger) {
            case Trigger.HttpStatus s -> RETRYABLE_STATUS_CODES.contains(s.statusCode());
            case Trigger.IoFailure ignored -> true;
            case Trigger.Timeout ignored -> true;
        };
    }

    private Duration computeDelay(int attempt, Trigger trigger) {
        // Honor Retry-After header on 429/503 when server provides it
        if (trigger instanceof Trigger.HttpStatus s && !s.retryAfterHint().isZero()) {
            return s.retryAfterHint();
        }
        // Exponential backoff with full jitter: random(0, min(maxBackoff, baseDelay * 2^attempt))
        long cap = Math.min(MAX_BACKOFF.toMillis(), baseDelay.toMillis() * (1L << Math.min(attempt, 30)));
        long jitter = cap == 0 ? 0 : (long) (random.nextDouble() * cap);
        return Duration.ofMillis(jitter);
    }
}
