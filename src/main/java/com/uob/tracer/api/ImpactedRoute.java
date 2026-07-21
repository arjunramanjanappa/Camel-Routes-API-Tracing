package com.uob.tracer.api;

/**
 * A route to re-test because a shared {@code @Component} class it uses was changed by the release,
 * tagged by how it relates to the release version being analysed, and with the API that owns the route
 * so no manual back-tracing is needed.
 *
 * @param api      the REST API path that resolves to this route (e.g. {@code /getStatus}), or null if unknown
 * @param route    the route id, e.g. {@code R9.8_getStatusRoute}
 * @param category {@link #CURRENT}, {@link #BAU} or {@link #FUTURE}
 */
public record ImpactedRoute(String api, String route, String category) {

    /** The release version's own route — the change is part of this release. */
    public static final String CURRENT = "Current";
    /** The current production baseline — the immediate-lower version, or the base route if none. */
    public static final String BAU = "BAU";
    /** A higher/future release version that would otherwise miss this change under its own version. */
    public static final String FUTURE = "Future";
}
