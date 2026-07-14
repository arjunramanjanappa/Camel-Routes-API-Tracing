package com.uob.tracer.api;

import java.util.List;
import java.util.Map;

/**
 * Per-API result of correlating an uploaded log against the traced footprint.
 * The UI renders one row per API, colour-coded by {@link #status()}.
 *
 * @param api            controller path (as traced)
 * @param operation      controller operation name
 * @param resolvedRoute  the route this API resolves to at the client version
 * @param clientVersion  the client release the analysis was run for
 * @param status         the end-to-end verdict (drives the row colour)
 * @param tested         false ⇒ never seen for this client release (status NOT_TESTED)
 * @param feLatencyMs    front-end response time of the latest attempt ([Nms])
 * @param responseCode   front-end responseCode of the latest attempt (shown when not green)
 * @param responseDescription front-end responseDescription of the latest attempt
 * @param attempts       how many transactions (correlation ids) hit this API
 * @param successCount   how many of those ended SUCCESS
 * @param failureCount   attempts - successCount
 * @param latestAt       timestamp of the latest attempt (the one shown)
 * @param correlationId  correlation id of the latest attempt
 * @param note           human-readable explanation for non-green / investigate states
 * @param backends       per-backend outcomes within the latest attempt
 * @param failuresByCode failed attempts grouped by response code / failure reason → count,
 *                       ordered most-frequent first (for investigating recurring errors)
 */
public record ApiLogResult(
        String api,
        String operation,
        String resolvedRoute,
        String clientVersion,
        LogStatus status,
        boolean tested,
        Integer feLatencyMs,
        String responseCode,
        String responseDescription,
        int attempts,
        int successCount,
        int failureCount,
        String latestAt,
        String correlationId,
        String note,
        List<BackendCallResult> backends,
        Map<String, Integer> failuresByCode) {
}
