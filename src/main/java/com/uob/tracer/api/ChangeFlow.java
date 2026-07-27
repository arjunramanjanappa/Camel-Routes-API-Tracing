package com.uob.tracer.api;

/**
 * One release-version "flow" to be tested: a distinct release route that calls a backend at a
 * given service version. Keyed by the SETTING route so two routes hitting the SAME backend at the
 * SAME service version stay two flows (they can run different payload logic and must each be
 * exercised — scenario 6). BAU (lower/unchanged-version) reuse is NOT recorded here.
 *
 * @param routeId        the release route that owns this flow (e.g. R9.14_ftOwnAccountSubmit)
 * @param backend        the backend api it calls (e.g. {{baseUrl}}/bfs/ft/own/submit)
 * @param serviceVersion the service version its template sends, or null when the route sets none
 */
public record ChangeFlow(String routeId, String backend, String serviceVersion) {
}
