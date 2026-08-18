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
 * @param branchRoute    the route a dynamic {@code direct:${FINAL_ROUTE_NAME}} choice branch resolved to,
 *                       when this flow is reached through one — so the results can show the full path
 *                       (branch → owning route → backend) and two branches converging on one shared route
 *                       stay two distinct flows. Null for a normal (non-dynamic-dispatch) flow.
 * @param hosturl        the "hostUrl" property this flow's route set — the path the host actually logs for
 *                       this backend at this version. Kept per-flow so one api reached via two routes with
 *                       DIFFERENT hostUrls (and versions) matches each version's own logged path. Null when
 *                       the route set no hostUrl (then the api value itself is the match path).
 */
public record ChangeFlow(String routeId, String backend, String serviceVersion, String branchRoute, String hosturl) {
}
