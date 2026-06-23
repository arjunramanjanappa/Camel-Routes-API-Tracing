package com.arjun.tracer.api;

import java.util.List;

/**
 * What a release changed for one API, comparing its whole resolved flow at the
 * target version against the same flow at the immediate-lower version.
 *
 * <p>{@code status} is one of:
 * <ul>
 *   <li>{@code NEW} — no lower version exists; the API was added in this release;</li>
 *   <li>{@code CHANGED} — the flow differs from the immediate-lower version
 *       (route bodies changed, or whole sub-routes were added / removed);</li>
 *   <li>{@code UNCHANGED} — a lower version exists but the resolved flow is
 *       identical (a version bump with no behavioural change).</li>
 * </ul>
 *
 * @param api           the front-end REST path
 * @param operation     the operation / controller method name
 * @param targetRoute   the entry route at the target version (e.g. {@code R9.18_xApi})
 * @param targetVersion the target version actually used for the entry route (e.g. {@code 9.18})
 * @param lowerRoute    the entry route at the immediate-lower version, or null when NEW
 * @param lowerVersion  the immediate-lower version compared against ({@code 9.14}, or {@code BASE}), null when NEW
 * @param status                NEW · CHANGED · UNCHANGED
 * @param routeDiffs            per-route structural diffs for routes present in both flows
 * @param addedRoutes           base names of sub-routes the target flow calls that the lower flow did not
 * @param removedRoutes         base names of sub-routes the lower flow called that the target flow does not
 * @param backendVersionChanges backends whose resolved service version changed (e.g. 2.2 → 2.3), even when the route XML is otherwise unchanged
 * @param note                  optional explanation, e.g. when the API has no route at the target version and still resolves to a lower one
 */
public record ApiDiff(String api, String operation,
                      String targetRoute, String targetVersion,
                      String lowerRoute, String lowerVersion,
                      String status,
                      List<RouteStepDiff> routeDiffs,
                      List<String> addedRoutes, List<String> removedRoutes,
                      List<BackendVersionChange> backendVersionChanges,
                      String note) {

    public static final String NEW = "NEW";
    public static final String CHANGED = "CHANGED";
    public static final String UNCHANGED = "UNCHANGED";
}
