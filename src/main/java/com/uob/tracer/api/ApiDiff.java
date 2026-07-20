package com.uob.tracer.api;

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
 * @param authors               for a NEW API, the git-blame authors who added its routes (empty otherwise / when not a git work tree)
 * @param codeChanged           true when a Java class (a {@code @Component} bean) or route XML wired into this API's
 *                              flow was changed by the app-version release — independent of the version-to-version diff
 * @param changedClasses        display labels of changed bean classes in the flow, e.g. {@code statusProcessor (StatusProcessor.java)}
 * @param changedRoutes         base names of routes in the flow whose XML the release changed (whitespace-insensitive)
 * @param crossVersionRoutes    routes in the flow at a DIFFERENT release version than this API's target that the code
 *                              change also touches — the shared-code alert (e.g. releasing 9.18 but 9.14's route must be re-tested)
 */
public record ApiDiff(String api, String operation,
                      String targetRoute, String targetVersion,
                      String lowerRoute, String lowerVersion,
                      String status,
                      List<RouteStepDiff> routeDiffs,
                      List<String> addedRoutes, List<String> removedRoutes,
                      List<BackendVersionChange> backendVersionChanges,
                      PayloadChange payloadChange,
                      String note,
                      List<String> authors,
                      boolean codeChanged,
                      List<String> changedClasses,
                      List<String> changedRoutes,
                      List<String> crossVersionRoutes) {

    public static final String NEW = "NEW";
    public static final String CHANGED = "CHANGED";
    public static final String UNCHANGED = "UNCHANGED";
    /** Not a diff: an N/A snapshot row — the latest (else base) route this API resolves to in scope. */
    public static final String SNAPSHOT = "SNAPSHOT";

    /** Backward-compatible constructor for the version-diff callers: no code-change info yet. */
    public ApiDiff(String api, String operation,
                   String targetRoute, String targetVersion,
                   String lowerRoute, String lowerVersion,
                   String status,
                   List<RouteStepDiff> routeDiffs,
                   List<String> addedRoutes, List<String> removedRoutes,
                   List<BackendVersionChange> backendVersionChanges,
                   PayloadChange payloadChange,
                   String note,
                   List<String> authors) {
        this(api, operation, targetRoute, targetVersion, lowerRoute, lowerVersion, status,
                routeDiffs, addedRoutes, removedRoutes, backendVersionChanges, payloadChange, note, authors,
                false, List.of(), List.of(), List.of());
    }

    /** A copy of this diff annotated with the release's Java/route code changes for the flow. */
    public ApiDiff withCodeChange(boolean codeChanged, List<String> changedClasses,
                                  List<String> changedRoutes, List<String> crossVersionRoutes) {
        return new ApiDiff(api, operation, targetRoute, targetVersion, lowerRoute, lowerVersion, status,
                routeDiffs, addedRoutes, removedRoutes, backendVersionChanges, payloadChange, note, authors,
                codeChanged, changedClasses, changedRoutes, crossVersionRoutes);
    }
}
