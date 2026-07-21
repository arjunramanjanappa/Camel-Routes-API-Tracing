package com.uob.tracer.api;

import java.util.List;

/**
 * A route to re-test because a shared {@code @Component} class it uses was changed by the release,
 * tagged by how it relates to the release version being analysed, and with the API that owns it so no
 * manual back-tracing is needed.
 *
 * <p>{@code routePath} is the chain from the API's controller-configured entry route to the actually-changed
 * route: for a change directly on the entry route it is just {@code [entryRoute]}; for a change on a route
 * reached indirectly ({@code entry} has {@code <to uri="direct:sub"/>}) it is {@code [entryRoute, …, subRoute]}.
 *
 * @param api       the REST API path that owns the entry route (e.g. {@code /getStatus}), or null if unknown
 * @param routePath the entry-route → … → changed-route chain (last element is the changed route)
 * @param category  {@link #CURRENT}, {@link #BAU} or {@link #FUTURE}
 */
public record ImpactedRoute(String api, List<String> routePath, String category) {

    /** The release version's own route — the change is part of this release. */
    public static final String CURRENT = "Current";
    /** The current production baseline — the immediate-lower version, or the base route if none. */
    public static final String BAU = "BAU";
    /** A higher/future release version that would otherwise miss this change under its own version. */
    public static final String FUTURE = "Future";

    /** The actually-changed route (the last hop in the chain). */
    public String route() {
        return routePath.isEmpty() ? null : routePath.get(routePath.size() - 1);
    }
}
