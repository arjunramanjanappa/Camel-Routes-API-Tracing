package com.uob.tracer.trace;

import com.uob.tracer.api.GraphNode;
import com.uob.tracer.api.RouteGraph;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.loader.RouteRegistry;
import com.uob.tracer.model.ChoiceElement;
import com.uob.tracer.model.ContainerElement;
import com.uob.tracer.model.RecipientListElement;
import com.uob.tracer.model.RouteElement;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.model.SetPropertyElement;
import com.uob.tracer.model.ToElement;
import com.uob.tracer.model.WhenElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks the neutral route model starting from the resolved entry route,
 * following {@code direct:} calls recursively, resolving the dynamic
 * {@code operationName} recipient, honouring {@code transferType} branches, and
 * collecting backend APIs. Populates the {@link RouteGraph} and the textual
 * flow / backend lists on the {@link TraceResponse}.
 */
public class RouteTraverser {

    /** A single-quoted or double-quoted literal, e.g. the {@code 'INTER'} in a when. */
    private static final Pattern QUOTED = Pattern.compile("['\"]([A-Za-z0-9_\\-]+)['\"]");
    /** A static {@code direct:NAME} reference inside an expression. */
    private static final Pattern DIRECT_REF = Pattern.compile("direct:([A-Za-z0-9_.\\-]+)");
    /** A bare, route-name-shaped constant (no scheme, path, placeholder or whitespace) — a DEST_ROUTE base. */
    private static final Pattern BARE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]*");
    /** The version prefix of a route id, e.g. {@code R9.14_foo} → {@code 9.14}. */
    private static final Pattern ROUTE_VERSION = Pattern.compile("^R(\\d+(?:\\.\\d+)*)_");
    /** Endpoint schemes treated as external backend calls when used in {@code <to>}. */
    private static final Set<String> EXTERNAL_SCHEMES = Set.of(
            "http", "https", "http4", "https4", "cxf", "cxfrs", "rest", "netty-http",
            "jetty", "ahc", "ahc-ws", "vertx-http", "undertow");

    private final RouteRegistry registry;
    private final RouteGraph graph;
    private final TraceResponse response;
    private final String transferType;
    /** Route name that the dynamic {@code operationName} recipient resolves to. */
    private final String operationRouteName;

    private final Set<String> expandedRoutes = new HashSet<>();
    private final List<String> backendsSeen = new ArrayList<>();
    // Host/terminal instance ids are keyed by the CALLING route, not a running counter. Calls from the
    // same caller route to the same host share one instance — so a shared intermediate route reached by
    // many APIs draws its downstream host ONCE (not once per API), while different caller routes (e.g.
    // each API's entry) still get distinct instances so their backends never merge. This map counts
    // per-(caller,host) multiplicity so a caller that calls the same host twice gets two instances,
    // consistently across the catalog's per-API traversers (each walks the caller body the same way).
    private final java.util.Map<String, Integer> callerHostSeq = new java.util.HashMap<>();

    /** Resolves a framework template {@code <to>} uri to its serviceVersionNumber (or null). */
    private final java.util.function.Function<String, String> templateVersion;
    /**
     * Resolves a base route name + client version (e.g. {@code acceptcoreinfo} at {@code 9.14}) to
     * the actual route it runs (e.g. {@code R9.14_acceptcoreinfo}), or null when it doesn't resolve
     * to a real route. This is how a {@code <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>}
     * is followed: a bean builds {@code FINAL_ROUTE_NAME} = version + base, which is the same version
     * resolution used for entry routes.
     */
    private final java.util.function.BiFunction<String, String, String> destRouteResolver;
    /**
     * The requested client version (blank/null when the user left it empty, e.g. Release Scope). When
     * present it wins — the bean uses the real ClientVersion header. When empty, the DEST_ROUTE is
     * resolved at the CALLING route's own version instead, so the dynamic target still resolves rather
     * than being flagged just because no version was typed.
     */
    private final String requestClientVersion;
    /** Service version from the most recent template {@code <to>}, applied to the next backend. */
    private String currentServiceVersion;
    private String currentHosturl;   // the route's "hosturl" property — what MightyHostMessage logs
    /** Resolved (existing) route from the most recent DEST_ROUTE-style setProperty — the dynamic toD target. */
    private String currentDestRoute;
    /**
     * The route name a DEST_ROUTE base DERIVES to (R&lt;version&gt;_base) when that route is NOT in scope —
     * followed anyway so the dynamic toD is flagged as "Route not found: R&lt;version&gt;_base" (naming the
     * missing route for review) rather than a generic "unresolved dynamic target".
     */
    private String currentDestMissing;

    public RouteTraverser(RouteRegistry registry, RouteGraph graph, TraceResponse response,
                          String transferType, String operationRouteName,
                          java.util.function.Function<String, String> templateVersion) {
        this(registry, graph, response, transferType, operationRouteName, templateVersion,
                (base, version) -> null, null);
    }

    public RouteTraverser(RouteRegistry registry, RouteGraph graph, TraceResponse response,
                          String transferType, String operationRouteName,
                          java.util.function.Function<String, String> templateVersion,
                          java.util.function.BiFunction<String, String, String> destRouteResolver,
                          String requestClientVersion) {
        this.registry = registry;
        this.graph = graph;
        this.response = response;
        this.transferType = (transferType == null || transferType.isBlank()) ? null : transferType.trim();
        this.operationRouteName = operationRouteName;
        this.templateVersion = templateVersion != null ? templateVersion : uri -> null;
        this.destRouteResolver = destRouteResolver != null ? destRouteResolver : (base, version) -> null;
        this.requestClientVersion = requestClientVersion;
    }

    /** Trace from the entry route, attaching it under the given API node. */
    public void trace(String entryRouteName, String apiNodeId) {
        visitRoute(entryRouteName, apiNodeId, null, new ArrayList<>());
    }

    /**
     * Visit a route referenced by an endpoint name (e.g. {@code direct:NAME}).
     * The route is labelled by its version-bearing {@code id} (e.g.
     * {@code R9.18_redirectRoute}) rather than its {@code from} endpoint, which
     * is often un-versioned. Returns the graph node id so a pending backend can
     * be attached to a host route.
     */
    private String visitRoute(String endpoint, String parentNodeId, String branch, List<PendingApi> inherited) {
        RouteModel route = registry.lookup(endpoint);
        // Prefer the route id (carries the version); fall back to the endpoint name.
        String identity = (route != null && route.routeId() != null) ? route.routeId() : endpoint;
        String nodeId = "route:" + identity;
        String source = route != null ? route.source() : "not-found";
        boolean host = route != null && route.host();
        graph.addNode(new GraphNode(nodeId, identity, GraphNode.TYPE_ROUTE,
                java.util.Map.of("source", source, "host", host)));
        if (parentNodeId != null) {
            graph.addEdge(parentNodeId, nodeId, branch);
        }

        boolean firstVisit = expandedRoutes.add(identity);   // expand body once (loop guard)
        if (firstVisit) {
            // currentServiceVersion is intentionally NOT reset here: a template set in a caller route
            // (before it dispatches) is the request body for the backend the callee route calls, so it
            // must survive the hop. It is cleared when an api CONSUMES it instead (below), which stops
            // it leaking to a later api that has no template of its own.
            currentHosturl = null;          // hosturl (the logged backend path) is scoped to this route body
            currentDestRoute = null;        // and the DEST_ROUTE base is per-route-body
            currentDestMissing = null;
        }
        if (route == null) {
            if (firstVisit) {
                response.getWarnings().add("Route not found in source: " + endpoint);
            }
            attach(inherited, nodeId, false);                // still record the inherited backend(s)
            return nodeId;
        }
        if (host) {
            // The host performs the backend call. Attach the api (set here or
            // upstream) but do NOT expand the host's internal logic (its choice on
            // URI_PROTOCOL / camelHttpUri is not shown). Collect only the api it
            // sets itself, if any.
            List<PendingApi> collected = new ArrayList<>(inherited);
            if (firstVisit) {
                response.getFlow().add(identity);
                collected.addAll(collectApis(route.elements(), null));
            }
            attach(collected, nodeId, true);                 // backends fan INTO the host barrel
            return nodeId;
        }
        if (firstVisit) {
            response.getFlow().add(identity);
            List<PendingApi> active = new ArrayList<>(inherited);
            // Leftover = api set in/inherited by this route that no downstream route
            // consumed → this route is itself the consumer.
            attach(walk(route.elements(), nodeId, null, active, true), nodeId, false);
        } else {
            attach(inherited, nodeId, false);                // revisit: record inherited here (deduped)
        }
        return nodeId;
    }

    /** @param into true to draw backend → node (into a host barrel); false for node → backend. */
    private void attach(List<PendingApi> apis, String nodeId, boolean into) {
        for (PendingApi p : apis) {
            addBackend(p.value(), nodeId, p.branch(), into, p.serviceVersion(), p.hosturl());
        }
    }

    /**
     * Emit a per-call instance of a host / terminal route: {@code caller → host#N
     * → backend(s)}. Duplicating the host per call keeps backends segregated by the
     * route that triggered them instead of aggregating onto one shared host node.
     */
    private void emitConsumerInstance(RouteModel route, String endpoint, String callerNodeId,
                                      String edgeLabel, List<PendingApi> inherited) {
        String identity = route.routeId() != null ? route.routeId() : endpoint;
        // One instance per (caller route, host); a second call from the same caller to the same host
        // gets ".2", etc. Deterministic per caller, so the same shared caller in another API's traversal
        // resolves to the SAME instance id and de-duplicates in the shared catalog graph.
        String callerId = callerNodeId != null ? callerNodeId.replaceFirst("^route:", "") : "root";
        int n = callerHostSeq.merge(callerId + ' ' + identity, 1, Integer::sum);
        String instanceId = "route:" + identity + "#" + callerId + (n > 1 ? "." + n : "");
        graph.addNode(new GraphNode(instanceId, identity, GraphNode.TYPE_ROUTE,
                java.util.Map.of("source", route.source(), "host", route.host())));
        graph.addEdge(callerNodeId, instanceId, edgeLabel);
        if (expandedRoutes.add(identity)) {       // list the route once in the textual flow
            response.getFlow().add(identity);
        }
        List<PendingApi> all = new ArrayList<>(inherited);
        all.addAll(collectApis(route.elements(), null));   // the host's own api, if it sets one
        for (PendingApi p : all) {
            addBackend(p.value(), instanceId, p.branch(), false, p.serviceVersion(), p.hosturl());   // host instance → backend
        }
    }

    /** True if a route hands off to another route via direct:/seda:/vm: (i.e. it is not terminal). */
    private boolean forwardsFurther(List<RouteElement> elements) {
        for (RouteElement el : elements) {
            if (el instanceof ToElement to && to.uri() != null) {
                int c = to.uri().indexOf(':');
                String scheme = c > 0 ? to.uri().substring(0, c) : to.uri();
                if (scheme.equals("direct") || scheme.equals("direct-vm")
                        || scheme.equals("seda") || scheme.equals("vm")) {
                    return true;
                }
            } else if (el instanceof ChoiceElement choice) {
                for (WhenElement when : choice.whens()) {
                    if (forwardsFurther(when.children())) {
                        return true;
                    }
                }
                if (forwardsFurther(choice.otherwise())) {
                    return true;
                }
            } else if (el instanceof ContainerElement container) {
                if (forwardsFurther(container.children())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collect {@code setProperty name="api"} values within a host route WITHOUT
     * traversing its routing — a host's internal logic (its choice on the URI
     * protocol / camelHttpUri) is not expanded onto the graph.
     */
    private List<PendingApi> collectApis(List<RouteElement> elements, String branch) {
        List<PendingApi> out = new ArrayList<>();
        for (RouteElement el : elements) {
            if (el instanceof ToElement to && isTemplateUri(to.uri())) {
                applyTemplateVersion(to.uri(), out);
            } else if (el instanceof SetPropertyElement sp) {
                if (isHosturl(sp)) {
                    currentHosturl = sp.value().trim();
                } else if (isApi(sp)) {
                    out.add(new PendingApi(sp.value().trim(), branch, currentServiceVersion, currentHosturl));
                    currentServiceVersion = null;   // consumed
                }
            } else if (el instanceof ChoiceElement choice) {
                for (WhenElement when : choice.whens()) {
                    out.addAll(collectApis(when.children(), branchLabel(when.predicate())));
                }
                out.addAll(collectApis(choice.otherwise(), "OTHERWISE"));
            } else if (el instanceof ContainerElement container) {
                out.addAll(collectApis(container.children(), branch));
            }
            // ToElement / RecipientListElement intentionally not traversed for hosts
        }
        return out;
    }

    /**
     * Walk a sequence of steps, carrying the active {@code api} values down the
     * call chain. {@code setProperty name="api"} adds to {@code active}; a
     * {@code direct:} hand-off propagates {@code active} into the target route
     * (when {@code forward}) so the backend lands on the route that actually makes
     * the call — even if the api was set in an ancestor. Each {@code <choice>}
     * branch contributes its api tagged with the branch condition.
     *
     * @param forward true to propagate api values to {@code direct:} targets;
     *                false when the current route is a host that must keep them
     * @return the api values still un-consumed at the end of this scope
     */
    private List<PendingApi> walk(List<RouteElement> elements, String currentNodeId,
                                  String branch, List<PendingApi> active, boolean forward) {
        for (RouteElement el : elements) {
            if (el instanceof ToElement to) {
                if (isTemplateUri(to.uri())) {
                    // framework template carries the backend service version — applies to the
                    // next setProperty api, or back-fills one set just before it (template after).
                    applyTemplateVersion(to.uri(), active);
                } else {
                    handleTo(to.uri(), currentNodeId, branch, active, forward);
                }
            } else if (el instanceof RecipientListElement rl) {
                handleRecipient(rl.expression(), currentNodeId, branch);
            } else if (el instanceof SetPropertyElement sp) {
                if (isHosturl(sp)) {
                    currentHosturl = sp.value().trim();
                } else if (isApi(sp)) {
                    active.add(new PendingApi(sp.value().trim(), branch, currentServiceVersion, currentHosturl));
                    currentServiceVersion = null;   // consumed — a later api needs its own template to get a version
                } else {
                    // A DEST_ROUTE-style base name (e.g. acceptcoreinfo): remember the route it resolves
                    // to at this client version, so a following dynamic direct: toD can follow it. Property
                    // names vary by module, so we key on "a constant that resolves to a real route", not a name.
                    trackDestRoute(sp.value(), currentNodeId);
                }
            } else if (el instanceof ChoiceElement choice) {
                handleChoice(choice, currentNodeId, branch, active, forward);
            } else if (el instanceof ContainerElement container) {
                walk(container.children(), currentNodeId, branch, active, forward);
            }
            // WhenElement never appears at this level (only inside ChoiceElement)
        }
        return active;
    }

    /** Camel template-component schemes whose {@code <to>} carries a request body template. */
    private static final Set<String> TEMPLATE_SCHEMES = Set.of(
            "framework", "freemarker", "velocity", "mvel", "mustache",
            "thymeleaf", "string-template", "stringtemplate", "chunk");

    /** A template step: a template-component scheme, or a .ftl/.vm/… template file uri. */
    private static boolean isTemplateUri(String uri) {
        if (uri == null) {
            return false;
        }
        String u = uri.toLowerCase();
        int colon = u.indexOf(':');
        String scheme = colon > 0 ? u.substring(0, colon) : "";
        return TEMPLATE_SCHEMES.contains(scheme) || u.matches(".*\\.(ftl|ftlh|vm|vtl|mustache|peb)(\\?.*)?$");
    }

    private void handleTo(String uri, String currentNodeId, String branch,
                          List<PendingApi> active, boolean forward) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        int colon = uri.indexOf(':');
        String scheme = colon > 0 ? uri.substring(0, colon) : uri;
        String remainder = colon > 0 ? uri.substring(colon + 1) : "";
        int query = remainder.indexOf('?');
        if (query >= 0) {
            remainder = remainder.substring(0, query);       // strip endpoint options, e.g. seda:x?waitForTaskToComplete=Never
        }
        boolean async = scheme.equals("seda") || scheme.equals("vm");

        if (scheme.equals("direct") || scheme.equals("direct-vm") || async) {
            String target = resolveDynamicName(remainder);
            if (target == null) {
                // A dynamic direct: whose expression we can't read directly (e.g.
                // ${exchangeProperty[FINAL_ROUTE_NAME]}). If a DEST_ROUTE-style base was set, follow the
                // route it derives to — resolving cleanly when that route is in scope, or being flagged
                // BY NAME ("Route not found: R<version>_base") when the derived route is missing.
                if (currentDestRoute != null) {
                    target = currentDestRoute;
                } else if (currentDestMissing != null) {
                    target = currentDestMissing;   // derived name; visitRoute will flag it as not-found
                } else {
                    // No readable base at all — the base is set by a bean (not an XML <constant>), so it
                    // can't be derived statically. Name the route it's in so the review item is specific.
                    response.getWarnings().add("Unresolved dynamic target in "
                            + routeLabel(currentNodeId) + ": " + uri);
                    return;
                }
            }
            // Flag async (seda/vm) calls on the edge so they read as fire-and-forget.
            String edgeLabel = async
                    ? (branch != null && !branch.isBlank() ? branch + " · async" : "async")
                    : branch;
            RouteModel targetRoute = registry.lookup(target);
            boolean terminalConsumer = targetRoute != null
                    && (targetRoute.host() || !forwardsFurther(targetRoute.elements()));
            if (forward && !active.isEmpty() && terminalConsumer) {
                // This call hands a backend to a host / terminal route. Give it its OWN
                // instance node so the route→host→backend chain is per-call, not an
                // aggregated shared node where you cannot tell which route used which backend.
                emitConsumerInstance(targetRoute, target, currentNodeId, edgeLabel, active);
                active.clear();
            } else if (forward) {
                visitRoute(target, currentNodeId, edgeLabel, new ArrayList<>(active));
                active.clear();                              // handed off downstream
            } else {
                visitRoute(target, currentNodeId, edgeLabel, new ArrayList<>());
            }
        } else if (EXTERNAL_SCHEMES.contains(scheme)) {
            addBackend(uri, currentNodeId, branch, false, currentServiceVersion, currentHosturl); // external call is itself a backend
        }
        // bean:/log:/mock: etc. are not flow edges — ignore.
    }

    private void handleRecipient(String expression, String currentNodeId, String branch) {
        if (expression == null) {
            return;
        }
        // The framework's redirect uses direct:${exchangeProperty[operationName]}.
        if (expression.contains("operationName") && operationRouteName != null) {
            visitRoute(operationRouteName, currentNodeId, branch, new ArrayList<>());
            return;
        }
        Matcher m = DIRECT_REF.matcher(expression);
        boolean any = false;
        while (m.find()) {
            any = true;
            visitRoute(m.group(1), currentNodeId, branch, new ArrayList<>());
        }
        if (!any) {
            response.getWarnings().add("Dynamic recipientList not resolved in "
                    + routeLabel(currentNodeId) + ": " + expression);
        }
    }

    /** The route identity from a graph node id like {@code route:R9.18_foo} or {@code route:R9.18_foo#3}. */
    private static String routeLabel(String nodeId) {
        if (nodeId == null) {
            return "?";
        }
        String s = nodeId.startsWith("route:") ? nodeId.substring("route:".length()) : nodeId;
        int hash = s.indexOf('#');
        return hash >= 0 ? s.substring(0, hash) : s;
    }

    /** The version stamped on the route currently being walked (e.g. {@code R9.14_foo} → {@code 9.14}), or null. */
    private static String routeVersion(String nodeId) {
        Matcher m = ROUTE_VERSION.matcher(routeLabel(nodeId));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Walk the selected choice branches. Each branch's un-consumed api (set in the
     * branch, no in-branch host call) is added to {@code active} tagged with the
     * branch condition, so a host call after the choice fans out to one backend
     * per branch.
     */
    private void handleChoice(ChoiceElement choice, String currentNodeId, String branch,
                              List<PendingApi> active, boolean forward) {
        List<WhenElement> whens = choice.whens();
        if (transferType == null) {
            for (WhenElement when : whens) {
                active.addAll(walkBranch(when.children(), currentNodeId, branchLabel(when.predicate()), forward));
            }
            if (!choice.otherwise().isEmpty()) {
                active.addAll(walkBranch(choice.otherwise(), currentNodeId, "OTHERWISE", forward));
            }
            return;
        }
        // Filtered: only the branch(es) whose predicate matches the transferType.
        boolean matched = false;
        for (WhenElement when : whens) {
            if (predicateMatches(when.predicate(), transferType)) {
                matched = true;
                active.addAll(walkBranch(when.children(), currentNodeId, branchLabel(when.predicate()), forward));
            }
        }
        if (!matched) {
            if (!choice.otherwise().isEmpty()) {
                active.addAll(walkBranch(choice.otherwise(), currentNodeId, "OTHERWISE", forward));
            } else {
                response.getWarnings().add(
                        "transferType '" + transferType + "' matched no branch and there is no otherwise");
            }
        }
    }

    /** Walk one choice branch with its own fresh api scope; return what it leaves un-consumed. */
    private List<PendingApi> walkBranch(List<RouteElement> elements, String currentNodeId,
                                        String branch, boolean forward) {
        // Each branch inherits the service version in scope before the choice, but a
        // template inside one branch must not leak to sibling branches or after the choice.
        String savedServiceVersion = currentServiceVersion;
        String savedHosturl = currentHosturl;
        String savedDestRoute = currentDestRoute;   // each branch sets its own DEST_ROUTE → its own toD target
        String savedDestMissing = currentDestMissing;
        try {
            return walk(elements, currentNodeId, branch, new ArrayList<>(), forward);
        } finally {
            currentServiceVersion = savedServiceVersion;
            currentHosturl = savedHosturl;
            currentDestRoute = savedDestRoute;
            currentDestMissing = savedDestMissing;
        }
    }

    /**
     * Apply a framework template's service version. New apis after it pick it up via
     * {@code currentServiceVersion}; an api set just BEFORE it (template-after-setProperty,
     * e.g. inside choice branches with the template after the choice) is back-filled.
     */
    private void applyTemplateVersion(String uri, List<PendingApi> pending) {
        response.getTemplateUris().add(uri);   // remember every request-body template, for the payload diff
        String sv = templateVersion.apply(uri);
        currentServiceVersion = sv;
        if (sv == null) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            PendingApi p = pending.get(i);
            if (p.serviceVersion() == null) {
                pending.set(i, new PendingApi(p.value(), p.branch(), sv, p.hosturl()));
            }
        }
    }

    /** The backend API property: {@code name="api"} or any name ENDING in "api" (e.g. backendApi,
     *  targetApi) — case-insensitive — so every repo's naming is covered. */
    private static boolean isApi(SetPropertyElement sp) {
        return endsWith(sp, "api") && sp.value() != null && !sp.value().isBlank();
    }

    /** The host-URL property: {@code name="hosturl"} or any name ENDING in "hosturl" (e.g.
     *  backendHosturl) — case-insensitive. Checked before {@link #isApi} in the walk. */
    private static boolean isHosturl(SetPropertyElement sp) {
        return endsWith(sp, "hosturl") && sp.value() != null && !sp.value().isBlank();
    }

    private static boolean endsWith(SetPropertyElement sp, String suffix) {
        return sp.name() != null && sp.name().trim().toLowerCase(java.util.Locale.ROOT).endsWith(suffix);
    }

    private void addBackend(String value, String routeNodeId, String branch, boolean into,
                            String serviceVersion, String hosturl) {
        String nodeId = "backend:" + value;
        graph.addNode(new GraphNode(nodeId, value, GraphNode.TYPE_BACKEND));
        if (into) {
            graph.addEdge(nodeId, routeNodeId, branch);      // backend → host (converges into the barrel)
        } else {
            graph.addEdge(routeNodeId, nodeId, branch);      // route → backend
        }
        if (!backendsSeen.contains(value)) {
            backendsSeen.add(value);
            response.getBackendApis().add(value);
        }
        if (hosturl != null && !hosturl.isBlank()) {
            // The api is the backend identity (shown in the graph); the hosturl is what
            // the host actually logs (MightyHostMessage), so the log analysis matches on it.
            response.getBackendHosturls().putIfAbsent(value, hosturl.trim());
        }
        if (serviceVersion != null && !serviceVersion.isBlank()) {
            // A backend URL may be called with several versions (different branches /
            // templates) — accumulate the distinct ones, e.g. "2.2 / 3.3".
            String joined = response.getBackendVersions().merge(value, serviceVersion, (existing, add) -> {
                for (String v : existing.split(" / ")) {
                    if (v.equals(add)) {
                        return existing;
                    }
                }
                return existing + " / " + add;
            });
            graph.setBackendServiceVersion(nodeId, joined);
        }
    }

    /**
     * Track a DEST_ROUTE-style base name: when this {@code setProperty} value is a bare name that
     * resolves to a real route, remember that route so a following dynamic {@code direct:} toD can
     * follow it. The version used is the requested client version when given (the real ClientVersion
     * header), else the CALLING route's own version — so an empty version doesn't leave it unresolved.
     * Non-name values (URLs, expressions, flags) are ignored.
     */
    private void trackDestRoute(String value, String currentNodeId) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (v.isEmpty() || !BARE_NAME.matcher(v).matches()) {
            return;   // cheap skip for URLs / expressions / placeholders that can't be a route base
        }
        String version = (requestClientVersion != null && !requestClientVersion.isBlank())
                ? requestClientVersion : routeVersion(currentNodeId);
        String resolved = destRouteResolver.apply(v, version);
        if (resolved != null) {
            currentDestRoute = resolved;               // derives to a real route in scope
        } else if (version != null && !version.isBlank()) {
            // A readable base whose derived route isn't in scope: remember the name the bean would
            // build (R<version>_base) so the dynamic toD flags it BY NAME as not-found for review.
            currentDestMissing = "R" + version + "_" + v;
        }
    }

    /**
     * Resolve a {@code direct:} endpoint name that may be dynamic. A literal name
     * is returned as-is; {@code ${exchangeProperty[operationName]}} resolves to the
     * entry route; any other unresolved expression yields null.
     */
    private String resolveDynamicName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (!name.contains("${")) {
            return name; // static
        }
        if (name.contains("operationName") && operationRouteName != null) {
            return operationRouteName;
        }
        return null;
    }

    private boolean predicateMatches(String predicate, String transferType) {
        if (predicate == null) {
            return false;
        }
        String value = quotedValue(predicate);
        if (value != null && value.equalsIgnoreCase(transferType)) {
            return true;
        }
        return predicate.toUpperCase().contains(transferType.toUpperCase());
    }

    /** A short, human-friendly label for a when branch. */
    private String branchLabel(String predicate) {
        String value = quotedValue(predicate);
        if (value != null) {
            return value;
        }
        if (predicate == null) {
            return null;
        }
        String trimmed = predicate.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 29) + "..." : trimmed;
    }

    private String quotedValue(String predicate) {
        if (predicate == null) {
            return null;
        }
        Matcher m = QUOTED.matcher(predicate);
        String last = null;
        while (m.find()) {
            last = m.group(1); // the compared constant, e.g. INTER
        }
        return last;
    }

    /** A deferred backend api value, tagged with the branch condition that set it. */
    private record PendingApi(String value, String branch, String serviceVersion, String hosturl) {
    }
}
