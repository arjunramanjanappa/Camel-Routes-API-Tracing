package com.arjun.tracer.api;

import java.util.List;

/**
 * The structural change in one Camel route between the target release and its
 * immediate-lower version, expressed as added / removed canonical lines.
 *
 * <p>Routes are paired by their base name (the part after the {@code R<ver>_}
 * prefix), so the entry route {@code R9.18_xApi} is compared against
 * {@code R9.14_xApi}. The lines are a generic, tag-agnostic canonicalisation of
 * the route body (every element, attribute and expression — not just beans), so
 * any difference the release introduced shows up regardless of what it touched.
 *
 * @param routeBase    the version-stripped route name (e.g. {@code xApi})
 * @param targetRoute  the route id in the target flow (e.g. {@code R9.18_xApi}), or null if the route only exists in the lower flow
 * @param lowerRoute   the route id in the lower flow (e.g. {@code R9.14_xApi}), or null if the route only exists in the target flow
 * @param added        canonical lines present in the target but not the lower version (added by the release)
 * @param removed      canonical lines present in the lower version but not the target (removed by the release)
 */
public record RouteStepDiff(String routeBase, String targetRoute, String lowerRoute,
                            List<String> added, List<String> removed) {
}
