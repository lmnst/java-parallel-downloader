package com.example.downloader.chaos;

/**
 * The fault classes the chaos adapter can inject on a single GET. Each
 * variant is documented in ChaosHttpAdapter's switch statement: the
 * docstring there is the single source of truth for response shape.
 */
public enum Fault {
    PASS_THROUGH,
    HTTP_408,
    HTTP_429,
    HTTP_500,
    HTTP_502,
    HTTP_503,
    HTTP_504,
    HTTP_200_ON_RANGED,
    TRUNCATED_BODY,
    MALFORMED_CONTENT_RANGE,
    MISMATCHED_CONTENT_RANGE,
    IO_MID_BODY,
    SLOWLORIS,
    CHUNK_DELAY_JITTER
}
