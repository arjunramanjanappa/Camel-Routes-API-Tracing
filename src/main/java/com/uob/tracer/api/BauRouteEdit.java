package com.uob.tracer.api;

import java.util.List;

/**
 * An in-place edit the release made to a <b>BAU route</b> — a pre-existing, non-release route (a version
 * below the release, or an un-versioned/base route) that the old app still runs. Found by git-diffing that
 * route's OWN XML across the release (its pre-release version vs the current one), which the version diff
 * (new route vs old route) fundamentally can't see.
 *
 * <p>Because the change lands on a route already in production it alters existing PROD behaviour:
 * <ul>
 *   <li><b>Removed</b> steps ({@code removedSteps} non-empty) are backward-INCOMPATIBLE — a step the old app
 *       relied on is gone → High + backward-compatibility required.</li>
 *   <li><b>Added</b>/other changes ({@code addedSteps}) still change PROD behaviour and are surfaced, but are
 *       backward-compatible, so they are risk-graded (Medium) rather than High.</li>
 * </ul>
 *
 * @param route        the BAU route id whose body changed (e.g. {@code R9.8_getStatusRoute})
 * @param path         the owning API's entry → … → route chain (for display)
 * @param addedSteps   canonical step lines present after the release but not before
 * @param removedSteps canonical step lines present before the release but not after
 * @param changedBy    git-blame authors of the route's current lines (empty when not a git work tree)
 */
public record BauRouteEdit(String route, List<String> path,
                           List<String> addedSteps, List<String> removedSteps,
                           List<String> changedBy) {
}
