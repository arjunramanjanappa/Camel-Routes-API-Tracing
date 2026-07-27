package com.uob.tracer.api;

import java.util.Map;

/**
 * One traced FLOW (a release route calling a backend at a service version) and its coverage across
 * all of an API's transactions. The {@code status} is the latest covering call's outcome; the
 * {@code attempts}/{@code passed}/{@code failed}/{@code failuresByCode} describe the whole distribution
 * of calls to this flow (its failure bar). NOT_TESTED means no transaction ever covered it.
 *
 * @param backend      the backend as the tracer knows it (e.g. {{baseUrl}}/bfs/ft/own/submit)
 * @param observedPath the path actually seen in the log (e.g. /bfs/ft/own/submit), null if never seen
 * @param status       latest covering call: SUCCESS / FAILED / TIMEOUT / INDETERMINATE, or NOT_TESTED
 * @param latencyMs    the [Nms] the backend took, when present on the response line
 * @param responseCode raw responseCode from the backend JSON (shown when not determinable)
 * @param responseDescription raw responseDescription from the backend JSON
 * @param expectedServiceVersion service version the tracer expects for this backend (may be "2.2 / 3.3")
 * @param loggedServiceVersion   service version actually seen in the host-message payload
 * @param serviceVersionOk       true if logged matches an expected version, false if mismatch, null if unknown
 * @param bau                    true when this row is a BAU reuse of the backend at a lower/unchanged service
 *                               version (a different behaviour than the release change) — shown labelled BAU
 *                               and never counted toward the API's pass/fail (nothing changed there to verify)
 * @param flowRoute    the release route that owns this flow (e.g. R9.14_routeB) — labels the row so two
 *                     routes on the same backend+version are distinct; null for a BAU / single-URL row
 * @param attempts     total calls observed to this flow across all transactions (0 → not tested)
 * @param passed       of those, how many succeeded
 * @param failed       of those, how many did not
 * @param failuresByCode failure responseCode → count, most-frequent first (the flow's failure breakdown)
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
        Boolean serviceVersionOk,
        boolean bau,
        String flowRoute,
        int attempts,
        int passed,
        int failed,
        Map<String, Integer> failuresByCode) {
}
