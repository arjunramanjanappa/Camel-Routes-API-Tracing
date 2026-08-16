package com.uob.tracer.api;

import java.util.List;
import java.util.Map;

/**
 * One API's footprint for impact analysis: the routes it traverses, the backend
 * APIs it calls, and the host routes involved — at a given client version.
 *
 * @param api             the front-end REST path (from the controller)
 * @param operation       the operation / controller method name
 * @param command         the UFW command, if any
 * @param resolvedRoute   the entry route it resolves to
 * @param resolvedVersion the version used (null for BASE)
 * @param baseFallback    true if it fell back to BASE
 * @param routes          every route id in its flow
 * @param backends        every backend API it calls
 * @param hosts           the host (CamelHttpUri) route ids it uses
 * @param backendVersions backend URL → service version number (from its framework template)
 * @param backendHosturls backend api → its "hosturl" property (the path logged by the host)
 * @param changeBackendVersions backend URL → service version, but only from routes at THIS release version
 *                              (the release's own change) — excludes lower/BAU routes reusing the same backend,
 *                              so release-test verification checks the change and ignores unchanged BAU calls
 * @param unconditionalBackends backends reached with no choice-branch condition (they always run) — a
 *                              release-version one never seen in the log is a real coverage gap; a
 *                              conditional/branch backend's absence is expected, not a gap
 * @param changeFlows   release-version flows to be tested, one per (setting route, backend, service
 *                      version) — keeps route multiplicity so two routes on the same backend+version
 *                      are two flows that must each be covered (scenario 6); BAU reuse excluded
 */
public record ApiImpact(String api, String operation, String command,
                        String resolvedRoute, String resolvedVersion, boolean baseFallback,
                        List<String> routes, List<String> backends, List<String> hosts,
                        Map<String, String> backendVersions, Map<String, String> backendHosturls,
                        Map<String, String> changeBackendVersions, List<String> unconditionalBackends,
                        List<ChangeFlow> changeFlows, List<ChangeFlow> bauFlows,
                        Map<String, java.util.Set<String>> branchConditions) {
}
