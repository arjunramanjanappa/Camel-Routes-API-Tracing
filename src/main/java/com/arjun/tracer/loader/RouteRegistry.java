package com.arjun.tracer.loader;

import com.arjun.tracer.model.RouteModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory index of all routes discovered in the source directory.
 *
 * <p>Routes are looked up by their {@code <from>} endpoint name (how callers
 * reference them via {@code direct:NAME}) and, as a secondary key, by route id.
 */
public class RouteRegistry {

    /** Matches versioned route names: {@code R9.4_<operation>} -> version, operation. */
    private static final Pattern VERSIONED =
            Pattern.compile("^R(\\d+(?:\\.\\d+)*)_(.+)$");

    private final Map<String, RouteModel> byFromName = new LinkedHashMap<>();
    private final Map<String, RouteModel> byRouteId = new LinkedHashMap<>();
    private final List<RouteModel> all = new ArrayList<>();

    public void add(RouteModel route) {
        all.add(route);
        if (route.fromName() != null) {
            byFromName.putIfAbsent(route.fromName(), route);
        }
        if (route.routeId() != null) {
            byRouteId.putIfAbsent(route.routeId(), route);
        }
    }

    /**
     * Resolve a route referenced as {@code direct:NAME}. Tries the from-endpoint
     * index first (the canonical way routes are wired together), then route id.
     */
    public RouteModel lookup(String name) {
        if (name == null) {
            return null;
        }
        RouteModel m = byFromName.get(name);
        if (m == null) {
            m = byRouteId.get(name);
        }
        return m;
    }

    public boolean contains(String name) {
        return lookup(name) != null;
    }

    public Collection<RouteModel> all() {
        return all;
    }

    public int size() {
        return all.size();
    }

    /**
     * All versions available for a given operation, derived from the route names
     * {@code R<version>_<operation>} present in the registry. Used by the version
     * resolver to pick the correct fallback.
     */
    public List<String> availableVersionsFor(String operationName) {
        List<String> versions = new ArrayList<>();
        for (RouteModel route : all) {
            String key = route.fromName() != null ? route.fromName() : route.routeId();
            if (key == null) {
                continue;
            }
            Matcher m = VERSIONED.matcher(key);
            if (m.matches() && m.group(2).equals(operationName)) {
                versions.add(m.group(1));
            }
        }
        return versions;
    }
}
