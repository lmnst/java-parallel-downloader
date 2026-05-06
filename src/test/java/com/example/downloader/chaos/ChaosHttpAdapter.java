package com.example.downloader.chaos;

import com.example.downloader.ByteRange;
import com.example.downloader.CancelToken;
import com.example.downloader.HttpAdapter;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory HttpAdapter that, on each GET, picks a random fault from the
 * supplied {@link FaultDistribution} and shapes the response accordingly.
 * Holds the byte body itself (no delegate) so it can manufacture truncated,
 * mid-body-faulting, and malformed-Content-Range responses cleanly.
 *
 * The adapter stores its seed so failing property runs can be replayed
 * by passing the same seed to the constructor.
 *
 * <h3>Past offenders (seeds that previously caught a bug)</h3>
 * <ul>
 *   <li>(none yet — record any here as the harness catches regressions)</li>
 * </ul>
 */
public final class ChaosHttpAdapter implements HttpAdapter {

    public static final String ETAG = "\"chaos-etag\"";

    private final byte[] body;
    private final HeadResponse headResponse;
    private final FaultDistribution dist;
    private final long seed;
    private final Random rng;

    private final AtomicInteger getCallCount = new AtomicInteger();

    public ChaosHttpAdapter(byte[] body, FaultDistribution dist, long seed) {
        this.body = body;
        this.headResponse = new HeadResponse(200, body.length, true, ETAG, null);
        this.dist = dist;
        this.seed = seed;
        this.rng = new Random(seed);
    }

    public long seed() { return seed; }

    public int getCallCount() { return getCallCount.get(); }

    @Override
    public HeadResponse head(URI uri) {
        return headResponse;
    }

    @Override
    public GetResponse get(URI uri, ByteRange range, String ifRange,
                           Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException {

        getCallCount.incrementAndGet();
        if (cancel.isCancelled()) throw new InterruptedException("cancelled before GET");

        Fault fault;
        synchronized (rng) {
            fault = dist.pick(rng);
        }

        switch (fault) {
            case PASS_THROUGH -> {
                return passThrough(range, ifRange, sink);
            }
            case HTTP_408 -> { return statusOnly(408, ifRange); }
            case HTTP_429 -> { return statusOnly(429, ifRange); }
            case HTTP_500 -> { return statusOnly(500, ifRange); }
            case HTTP_502 -> { return statusOnly(502, ifRange); }
            case HTTP_503 -> { return statusOnly(503, ifRange); }
            case HTTP_504 -> { return statusOnly(504, ifRange); }
            case HTTP_200_ON_RANGED -> {
                // Server ignores Range, returns full body. ifRangeMismatch tracks
                // whether we sent If-Range — required for resume-mode RESOURCE_CHANGED
                // detection. In FRESH mode the probe-chunk fallback consumes this
                // path; non-probe chunks reject 200 → HttpStatusException.
                sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
                return new GetResponse(200, body.length, null, ifRange != null);
            }
            case TRUNCATED_BODY -> {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
                    return new GetResponse(200, body.length, null, false);
                }
                int len = (int) range.length();
                int truncated = Math.max(1, len / 2);
                sink.accept(ByteBuffer.wrap(body, (int) range.offset(), truncated).asReadOnlyBuffer());
                return new GetResponse(206, truncated, contentRange(range), false);
            }
            case MALFORMED_CONTENT_RANGE -> {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
                    return new GetResponse(200, body.length, null, false);
                }
                int len = (int) range.length();
                sink.accept(ByteBuffer.wrap(body, (int) range.offset(), len).asReadOnlyBuffer());
                return new GetResponse(206, len, "this-is-not-a-content-range", false);
            }
            case MISMATCHED_CONTENT_RANGE -> {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
                    return new GetResponse(200, body.length, null, false);
                }
                int len = (int) range.length();
                sink.accept(ByteBuffer.wrap(body, (int) range.offset(), len).asReadOnlyBuffer());
                // Shift the reported start by 1 byte to make CR disagree with Range.
                long badStart = range.offset() + 1;
                long badEnd = range.lastByte() + 1;
                String cr = "bytes " + badStart + "-" + badEnd + "/" + body.length;
                return new GetResponse(206, len, cr, false);
            }
            case IO_MID_BODY -> {
                if (range == null) {
                    sink.accept(ByteBuffer.wrap(body, 0, Math.max(1, body.length / 2)).asReadOnlyBuffer());
                } else {
                    int len = Math.max(1, ((int) range.length()) / 3);
                    sink.accept(ByteBuffer.wrap(body, (int) range.offset(), len).asReadOnlyBuffer());
                }
                throw new IOException("simulated socket reset mid-body");
            }
            case SLOWLORIS -> {
                // 1 ms per byte, capped at 16 bytes so the per-fault cost stays
                // within ~16 ms even on platforms where Thread.sleep granularity
                // rounds up. The downloader sees a short body and fails the
                // chunk's truncation check on a real network this would also
                // tickle the request-timeout path; we don't simulate the
                // long-running case here because the in-process retry flow
                // already exercises the same shape.
                int from = range == null ? 0 : (int) range.offset();
                int wanted = range == null ? body.length : (int) range.length();
                int limit = Math.min(16, wanted);
                for (int i = 0; i < limit; i++) {
                    if (cancel.isCancelled()) throw new InterruptedException("cancelled mid-slowloris");
                    sink.accept(ByteBuffer.wrap(body, from + i, 1).asReadOnlyBuffer());
                    Thread.sleep(1);
                }
                return new GetResponse(range == null ? 200 : 206, limit,
                        range == null ? null : contentRange(range), false);
            }
            case CHUNK_DELAY_JITTER -> {
                int jitterMs;
                synchronized (rng) {
                    jitterMs = rng.nextInt(50);
                }
                Thread.sleep(jitterMs);
                return passThrough(range, ifRange, sink);
            }
        }
        throw new AssertionError("unhandled fault: " + fault);
    }

    private GetResponse passThrough(ByteRange range, String ifRange, Consumer<ByteBuffer> sink) {
        if (range == null) {
            sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
            return new GetResponse(200, body.length, null, false);
        }
        int from = (int) range.offset();
        int len = (int) range.length();
        sink.accept(ByteBuffer.wrap(body, from, len).asReadOnlyBuffer());
        return new GetResponse(206, len, contentRange(range), false);
    }

    private GetResponse statusOnly(int status, String ifRange) {
        // 200 with If-Range sent is "validator mismatch" — but our HTTP
        // status-only faults are all error codes (4xx/5xx), not 200, so the flag
        // is always false.
        return new GetResponse(status, 0, null, false);
    }

    private String contentRange(ByteRange r) {
        return "bytes " + r.offset() + "-" + r.lastByte() + "/" + body.length;
    }
}
