package com.arjun.tracer.api;

/**
 * Per-backend result of a backend-only (MightyHostMessage) analysis — when the
 * user selects backends to analyse without a front-end API. Reports whether the
 * backend was called for the release and whether those calls succeeded, read
 * straight from the host-message log lines.
 *
 * @param backend        the backend as the tracer knows it (e.g. {{baseUrl}}/bfs/payee/list)
 * @param status         SUCCESS / FAILED / TIMEOUT / INDETERMINATE / NOT_TESTED
 * @param tested         false ⇒ never observed for this release
 * @param latencyMs      latency of the latest call ([Nms])
 * @param responseCode   responseCode of the latest call (shown when not green)
 * @param responseDescription responseDescription of the latest call
 * @param attempts       how many host-message calls hit this backend at the release
 * @param successCount   how many ended SUCCESS
 * @param failureCount   attempts - successCount
 * @param latestAt       timestamp of the latest call
 * @param correlationId  correlation id of the latest call
 * @param note           explanation for non-green / investigate states
 */
public record BackendLogResult(
        String backend,
        LogStatus status,
        boolean tested,
        Integer latencyMs,
        String responseCode,
        String responseDescription,
        int attempts,
        int successCount,
        int failureCount,
        String latestAt,
        String correlationId,
        String note) {
}
