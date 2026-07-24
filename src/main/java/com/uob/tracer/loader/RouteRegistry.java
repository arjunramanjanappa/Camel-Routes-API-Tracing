package com.uob.tracer.loader;

import com.uob.tracer.model.ChoiceElement;
import com.uob.tracer.model.ContainerElement;
import com.uob.tracer.model.RouteElement;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.model.WhenElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** A Camel property placeholder {@code {{key}}} or {@code {{key:default}}} inside a route name. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final Map<String, RouteModel> byFromName = new LinkedHashMap<>();
    private final Map<String, RouteModel> byRouteId = new LinkedHashMap<>();
    private final List<RouteModel> all = new ArrayList<>();
    /**
     * Ambient routes: dependency routes NOT reachable from the country's own assembly closure. They are
     * wholesale-included so a {@code direct:} host resolves without an import (see SourceIndex), and so stay
     * lookup-able — but they must NOT count as one of THIS country's APIs/versions (that would let another
     * country's versioned route, e.g. {@code R6.0_validate} from {@code security-th-v1.xml}, become the
     * predecessor of an SG API). Identity set: excluded from operation/version enumeration only.
     */
    private final Set<RouteModel> ambient = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** application.properties (+ other source properties): resolves {@code {{key}}} in route names. */
    private final Map<String, String> properties;

    public RouteRegistry() {
        this(Map.of());
    }

    public RouteRegistry(Map<String, String> properties) {
        this.properties = properties == null ? Map.of() : properties;
    }

    /**
     * Substitute Camel property placeholders in a route name, so a hop like
     * {@code direct:logoutversion{{sso.version}}} resolves against a route named
     * {@code logoutversionV5} when {@code sso.version=V5} is defined in the source. A
     * {@code {{key:default}}} falls back to its default; an unknown key with no default is left as the
     * literal {@code {{key}}} so it never silently matches the wrong route (it surfaces as needs-review).
     */
    public String resolveName(String name) {
        if (name == null || name.indexOf("{{") < 0) {
            return name;
        }
        Matcher m = PLACEHOLDER.matcher(name);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            int c = expr.indexOf(':');
            String key = (c >= 0 ? expr.substring(0, c) : expr).trim();
            String val = properties.get(key);
            if (val == null && c >= 0) {
                val = expr.substring(c + 1);   // {{key:default}} → the default
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The lookup key for a route: its from-name (else route id), with placeholders resolved. */
    private String keyOf(RouteModel route) {
        String raw = route.fromName() != null ? route.fromName() : route.routeId();
        return resolveName(raw);
    }

    public void add(RouteModel route) {
        add(route, false);
    }

    /**
     * Add a route. {@code ambient} = a dependency route not reachable from the country closure: kept for
     * {@code direct:} host lookup, but excluded from this country's operation/version enumeration. Country
     * routes are added first (before ambient), so on a name clash the country's own route wins the index.
     */
    public void add(RouteModel route, boolean ambient) {
        all.add(route);
        if (ambient) {
            this.ambient.add(route);
        }
        if (route.fromName() != null) {
            byFromName.putIfAbsent(resolveName(route.fromName()), route);
        }
        if (route.routeId() != null) {
            byRouteId.putIfAbsent(resolveName(route.routeId()), route);
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
        String key = resolveName(name);   // direct:logoutversion{{sso.version}} -> logoutversionV5
        RouteModel m = byFromName.get(key);
        if (m == null) {
            m = byRouteId.get(key);
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
            if (ambient.contains(route)) {
                continue;   // another country's dependency route is not a version of this country's API
            }
            String key = keyOf(route);
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

    /**
     * The set of operation names that have a route here — the version-stripped name of every
     * route ({@code R9.4_xApi} → {@code xApi}; a BASE/un-versioned route keeps its own name).
     * Computed in one pass so callers can test "does this operation have a route?" in O(1)
     * instead of re-scanning every route per operation.
     */
    public Set<String> operationNames() {
        Set<String> ops = new LinkedHashSet<>();
        for (RouteModel route : all) {
            if (ambient.contains(route)) {
                continue;   // ambient dependency routes are not this country's own operations
            }
            String key = keyOf(route);
            if (key == null) {
                continue;
            }
            Matcher m = VERSIONED.matcher(key);
            ops.add(m.matches() ? m.group(2) : key);
        }
        return ops;
    }

    /** All distinct release versions present across every route (for UI suggestions). */
    public List<String> allVersions() {
        Set<String> versions = new LinkedHashSet<>();
        for (RouteModel route : all) {
            if (ambient.contains(route)) {
                continue;   // ambient dependency routes don't define this country's versions
            }
            String key = keyOf(route);
            if (key == null) {
                continue;
            }
            Matcher m = VERSIONED.matcher(key);
            if (m.matches()) {
                versions.add(m.group(1));
            }
        }
        return new ArrayList<>(versions);
    }

    /**
     * All distinct quoted constants compared in {@code <choice>}/{@code <when>}
     * predicates (e.g. {@code OWN}, {@code INTRA}, {@code INTER}) — the candidate
     * {@code transferType} values, for UI suggestions.
     */
    public List<String> allBranchValues() {
        Set<String> values = new LinkedHashSet<>();
        for (RouteModel route : all) {
            collectBranchValues(route.elements(), values);
        }
        return new ArrayList<>(values);
    }

    private void collectBranchValues(List<RouteElement> elements, Set<String> out) {
        for (RouteElement el : elements) {
            if (el instanceof ChoiceElement choice) {
                for (WhenElement when : choice.whens()) {
                    Matcher m = QUOTED.matcher(when.predicate() == null ? "" : when.predicate());
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                    collectBranchValues(when.children(), out);
                }
                collectBranchValues(choice.otherwise(), out);
            } else if (el instanceof ContainerElement container) {
                collectBranchValues(container.children(), out);
            }
        }
    }

    /** A single- or double-quoted literal, e.g. the {@code 'INTER'} in a when. */
    private static final Pattern QUOTED = Pattern.compile("['\"]([A-Za-z0-9_\\-]+)['\"]");
}
