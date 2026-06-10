package com.arjun.tracer.trace;

import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.RouteGraph;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.loader.RouteRegistry;
import com.arjun.tracer.model.ChoiceElement;
import com.arjun.tracer.model.ContainerElement;
import com.arjun.tracer.model.RecipientListElement;
import com.arjun.tracer.model.RouteElement;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.model.SetPropertyElement;
import com.arjun.tracer.model.ToElement;
import com.arjun.tracer.model.WhenElement;

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

    public RouteTraverser(RouteRegistry registry, RouteGraph graph, TraceResponse response,
                          String transferType, String operationRouteName) {
        this.registry = registry;
        this.graph = graph;
        this.response = response;
        this.transferType = (transferType == null || transferType.isBlank()) ? null : transferType.trim();
        this.operationRouteName = operationRouteName;
    }

    /** Trace from the entry route, attaching it under the given API node. */
    public void trace(String entryRouteName, String apiNodeId) {
        visitRoute(entryRouteName, apiNodeId, null);
    }

    /**
     * Visit a route referenced by an endpoint name (e.g. {@code direct:NAME}).
     * The route is labelled by its version-bearing {@code id} (e.g.
     * {@code R9.18_redirectRoute}) rather than its {@code from} endpoint, which
     * is often un-versioned. Returns the graph node id so a pending backend can
     * be attached to a host route.
     */
    private String visitRoute(String endpoint, String parentNodeId, String branch) {
        RouteModel route = registry.lookup(endpoint);
        // Prefer the route id (carries the version); fall back to the endpoint name.
        String identity = (route != null && route.routeId() != null) ? route.routeId() : endpoint;
        String nodeId = "route:" + identity;
        String source = route != null ? route.source() : "not-found";
        graph.addNode(new GraphNode(nodeId, identity, GraphNode.TYPE_ROUTE,
                java.util.Map.of("source", source)));
        if (parentNodeId != null) {
            graph.addEdge(parentNodeId, nodeId, branch);
        }
        if (expandedRoutes.add(identity)) {        // expand once (loop guard)
            if (route == null) {
                response.getWarnings().add("Route not found in source: " + endpoint);
            } else {
                response.getFlow().add(identity);
                List<PendingApi> leftover = walk(route.elements(), nodeId, null);
                // api set with no following host call anywhere: attach to this route.
                for (PendingApi p : leftover) {
                    addBackend(p.value(), nodeId, p.branch());
                }
            }
        }
        return nodeId;
    }

    /**
     * Walk a sequence of steps. The backend endpoint is carried by
     * {@code setProperty name="api"}, then the route hands off to a host route
     * (e.g. {@code direct:callUFWDGE}) that performs the call — so the api value
     * is deferred and attached to that host. Crucially this also covers the case
     * where each {@code <when>}/{@code <otherwise>} of a {@code <choice>} sets a
     * different api and the host call sits <em>after</em> the choice: those api
     * values (each tagged with its branch condition) bubble up here and attach to
     * the host, so the graph shows the host fanning out to one backend per branch.
     *
     * @return api values set in this scope that were not yet consumed by a host
     *         call (they bubble up to a host call in the enclosing scope).
     */
    private List<PendingApi> walk(List<RouteElement> elements, String currentNodeId, String branch) {
        List<PendingApi> pending = new ArrayList<>();
        for (RouteElement el : elements) {
            if (el instanceof ToElement to) {
                String targetNode = handleTo(to.uri(), currentNodeId, branch);
                if (targetNode != null && !pending.isEmpty()) {
                    for (PendingApi p : pending) {
                        addBackend(p.value(), targetNode, p.branch());   // host route → backend (per condition)
                    }
                    pending.clear();
                }
            } else if (el instanceof RecipientListElement rl) {
                handleRecipient(rl.expression(), currentNodeId, branch);
            } else if (el instanceof SetPropertyElement sp) {
                if (sp.name() != null && sp.name().equalsIgnoreCase("api")
                        && sp.value() != null && !sp.value().isBlank()) {
                    pending.add(new PendingApi(sp.value().trim(), branch));   // defer to the host call
                }
            } else if (el instanceof ChoiceElement choice) {
                pending.addAll(handleChoice(choice, currentNodeId, branch));
            } else if (el instanceof ContainerElement container) {
                pending.addAll(walk(container.children(), currentNodeId, branch));
            }
            // WhenElement never appears at this level (only inside ChoiceElement)
        }
        return pending;
    }

    /**
     * @return the graph node id of the route this {@code to} hands off to (so a
     *         pending backend api can be attached to it), or null for external /
     *         unresolved / non-routing endpoints.
     */
    private String handleTo(String uri, String currentNodeId, String branch) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        int colon = uri.indexOf(':');
        String scheme = colon > 0 ? uri.substring(0, colon) : uri;
        String remainder = colon > 0 ? uri.substring(colon + 1) : "";

        if (scheme.equals("direct") || scheme.equals("direct-vm") || scheme.equals("seda")
                || scheme.equals("vm")) {
            String target = resolveDynamicName(remainder);
            if (target != null) {
                return visitRoute(target, currentNodeId, branch);
            }
            response.getWarnings().add("Unresolved dynamic target: " + uri);
            return null;
        } else if (EXTERNAL_SCHEMES.contains(scheme)) {
            addBackend(uri, currentNodeId, branch); // external call, not a setProperty api
        }
        // bean:/log:/mock: etc. are not flow edges — ignore.
        return null;
    }

    private void handleRecipient(String expression, String currentNodeId, String branch) {
        if (expression == null) {
            return;
        }
        // The framework's redirect uses direct:${exchangeProperty[operationName]}.
        if (expression.contains("operationName") && operationRouteName != null) {
            visitRoute(operationRouteName, currentNodeId, branch);
            return;
        }
        Matcher m = DIRECT_REF.matcher(expression);
        boolean any = false;
        while (m.find()) {
            any = true;
            visitRoute(m.group(1), currentNodeId, branch);
        }
        if (!any) {
            response.getWarnings().add("Dynamic recipientList not resolved: " + expression);
        }
    }

    /**
     * Walk the selected choice branches, returning any api values they set that a
     * host call after the choice should consume (each tagged with its branch).
     */
    private List<PendingApi> handleChoice(ChoiceElement choice, String currentNodeId, String branch) {
        List<PendingApi> bubbled = new ArrayList<>();
        List<WhenElement> whens = choice.whens();
        if (transferType == null) {
            // No filter: explore every branch.
            for (WhenElement when : whens) {
                bubbled.addAll(walk(when.children(), currentNodeId, branchLabel(when.predicate())));
            }
            if (!choice.otherwise().isEmpty()) {
                bubbled.addAll(walk(choice.otherwise(), currentNodeId, "OTHERWISE"));
            }
            return bubbled;
        }
        // Filtered: only the branch(es) whose predicate matches the transferType.
        boolean matched = false;
        for (WhenElement when : whens) {
            if (predicateMatches(when.predicate(), transferType)) {
                matched = true;
                bubbled.addAll(walk(when.children(), currentNodeId, branchLabel(when.predicate())));
            }
        }
        if (!matched) {
            if (!choice.otherwise().isEmpty()) {
                bubbled.addAll(walk(choice.otherwise(), currentNodeId, "OTHERWISE"));
            } else {
                response.getWarnings().add(
                        "transferType '" + transferType + "' matched no branch and there is no otherwise");
            }
        }
        return bubbled;
    }

    private void addBackend(String value, String currentNodeId, String branch) {
        String nodeId = "backend:" + value;
        graph.addNode(new GraphNode(nodeId, value, GraphNode.TYPE_BACKEND));
        graph.addEdge(currentNodeId, nodeId, branch);
        if (!backendsSeen.contains(value)) {
            backendsSeen.add(value);
            response.getBackendApis().add(value);
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
    private record PendingApi(String value, String branch) {
    }
}
