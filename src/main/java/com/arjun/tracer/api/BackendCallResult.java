package com.arjun.tracer.api;

/**
 * One traced backend's observed outcome within the latest transaction for an API.
 *
 * @param backend      the backend as the tracer knows it (e.g. {{baseUrl}}/bfs/ft/own/submit)
 * @param observedPath the path actually seen in the log (e.g. /bfs/ft/own/submit), null if never seen
 * @param status       SUCCESS / FAILED / TIMEOUT / INDETERMINATE, or NOT_TESTED if never observed
 * @param latencyMs    the [Nms] the backend took, when present on the response line
 * @param responseCode raw responseCode from the backend JSON (shown when not determinable)
 * @param responseDescription raw responseDescription from the backend JSON
 * @param expectedServiceVersion service version the tracer expects for this backend (may be "2.2 / 3.3")
 * @param loggedServiceVersion   service version actually seen in the host-message payload
 * @param serviceVersionOk       true if logged matches an expected version, false if mismatch, null if unknown
 */
public record BackendCallResult(
        String backend,
        String observedPath,
        LogStatus status,
        Integer latencyMs,
        String responseCode,
        String responseDescription,
        String expectedServiceVersion,
        String loggedServiceVersion,
        Boolean serviceVersionOk) {
}
