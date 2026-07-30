package com.uob.tracer.api;

import java.util.List;

/**
 * An in-place change the release made to a <b>BAU route</b> — a pre-existing, non-release route (a version
 * below the release, or an un-versioned/base route) that the old app still runs. Found by git-diffing the
 * route's OWN definition across the release (its pre-release version vs the current one) — which the version
 * diff (new route vs old route) fundamentally can't see. Two kinds, both on the same route:
 * <ul>
 *   <li><b>Route body</b> — steps added/removed in the route XML ({@code addedSteps}/{@code removedSteps}).</li>
 *   <li><b>Payload</b> — request-body template keys added/removed ({@code addedKeys}/{@code removedKeys}), and
 *       scalar values changed in place ({@code changedValues}), when the release modified a template the BAU
 *       route sends. Value comparison is safe here because the same template file is diffed against its own
 *       pre-release self (same engine on both sides).</li>
 * </ul>
 *
 * <p>Because the change lands on a route already in production it alters existing PROD behaviour, so it is
 * <b>High risk</b> and requires the old app to be regression-tested (backward compatibility). A removed step or
 * removed payload key is backward-INCOMPATIBLE (the old app loses/omits something it relied on); an added one
 * still changes PROD behaviour and must be verified.
 *
 * @param route        the BAU route id whose definition changed (e.g. {@code R9.8_getStatusRoute})
 * @param path         the owning API's entry → … → route chain (for display)
 * @param addedSteps   canonical route-body step lines present after the release but not before
 * @param removedSteps canonical route-body step lines present before the release but not after
 * @param addedKeys     request-payload keys the release added to a template this route sends
 * @param removedKeys   request-payload keys the release removed from a template this route sends
 * @param changedValues scalar payload values the release changed in place (key present on both sides)
 * @param changedBy     git-blame authors of the route's current lines (empty when not a git work tree)
 */
public record BauRouteEdit(String route, List<String> path,
                           List<String> addedSteps, List<String> removedSteps,
                           List<String> addedKeys, List<String> removedKeys,
                           List<PayloadValueChange> changedValues,
                           List<String> changedBy) {
}
