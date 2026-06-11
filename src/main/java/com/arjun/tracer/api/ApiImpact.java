package com.arjun.tracer.api;

import java.util.List;

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
 */
public record ApiImpact(String api, String operation, String command,
                        String resolvedRoute, String resolvedVersion, boolean baseFallback,
                        List<String> routes, List<String> backends, List<String> hosts) {
}
