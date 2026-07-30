package com.uob.tracer.api;

/**
 * End-to-end verdict for an API (or one backend call) derived from an uploaded
 * log / Splunk export. Drives the colour coding in the impact-analysis report.
 */
public enum LogStatus {
    /** Front-end response all-zeros, every observed backend all-zeros. */
    SUCCESS,
    /** Front-end response present but its responseCode is not all-zeros. */
    FAILED,
    /** Front-end request seen but no matching response — timeout or server down. */
    TIMEOUT,
    /** Front-end succeeded but a backend it called failed or a traced backend never appeared. */
    PARTIAL,
    /** A response was logged but its pass/fail could not be determined (no parseable code). */
    INDETERMINATE,
    /** Excluded from the verdict by a host response-code rule (log-rules.json) — neither pass nor fail. */
    SKIPPED,
    /** No log entry for this API at the requested client release — it was never tested. */
    NOT_TESTED
}
