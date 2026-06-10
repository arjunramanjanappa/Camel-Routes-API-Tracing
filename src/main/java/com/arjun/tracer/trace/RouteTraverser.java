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

    private void visitRoute(String routeName, String parentNodeId, String branch) {
        String nodeId = "route:" + routeName;
        RouteModel route = registry.lookup(routeName);
        String source = route != null ? route.source() : "not-found";
        graph.addNode(new GraphNode(nodeId, routeName, GraphNode.TYPE_ROUTE,
                java.util.Map.of("source", source)));
        if (parentNodeId != null) {
            graph.addEdge(parentNodeId, nodeId, branch);
        }
        if (!expandedRoutes.add(routeName)) {
            return; // already expanded — edge recorded, but don't recurse (loop guard)
        }
        if (route == null) {
            response.getWarnings().add("Route not found in source: " + routeName);
            return;
        }
        response.getFlow().add(routeName);
        walk(route.elements(), nodeId, null);
    }

    private void walk(List<RouteElement> elements, String currentNodeId, String branch) {
        // The backend endpoint is carried by setProperty name="api", then the route
        // hands off to a host route (e.g. direct:callUFWDGE) that performs the call.
        // So defer the api value and attach it to that host route, not to this one.
        List<String> pendingApis = new ArrayList<>();
        for (RouteElement el : elements) {
            if (el instanceof ToElement to) {
                String targetNode = handleTo(to.uri(), currentNodeId, branch);
                if (targetNode != null && !pendingApis.isEmpty()) {
                    for (String api : pendingApis) {
                        addBackend(api, targetNode, branch);   // host route → backend
                    }
                    pendingApis.clear();
                }
            } else if (el instanceof RecipientListElement rl) {
                handleRecipient(rl.expression(), currentNodeId, branch);
            } else if (el instanceof SetPropertyElement sp) {
                if (sp.name() != null && sp.name().equalsIgnoreCase("api")
                        && sp.value() != null && !sp.value().isBlank()) {
                    pendingApis.add(sp.value().trim());        // defer to the host call
                }
            } else if (el instanceof ChoiceElement choice) {
                handleChoice(choice, currentNodeId, branch);
            } else if (el instanceof ContainerElement container) {
                walk(container.children(), currentNodeId, branch);
            }
            // WhenElement never appears at this level (only inside ChoiceElement)
        }
        // api set without a following host-route call: attach to this route directly.
        for (String api : pendingApis) {
            addBackend(api, currentNodeId, branch);
        }
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
                visitRoute(target, currentNodeId, branch);
                return "route:" + target;
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

    private void handleChoice(ChoiceElement choice, String currentNodeId, String branch) {
        List<WhenElement> whens = choice.whens();
        if (transferType == null) {
            // No filter: explore every branch.
            for (WhenElement when : whens) {
                walk(when.children(), currentNodeId, branchLabel(when.predicate()));
            }
            if (!choice.otherwise().isEmpty()) {
                walk(choice.otherwise(), currentNodeId, "OTHERWISE");
            }
            return;
        }
        // Filtered: only the branch(es) whose predicate matches the transferType.
        boolean matched = false;
        for (WhenElement when : whens) {
            if (predicateMatches(when.predicate(), transferType)) {
                matched = true;
                walk(when.children(), currentNodeId, branchLabel(when.predicate()));
            }
        }
        if (!matched) {
            if (!choice.otherwise().isEmpty()) {
                walk(choice.otherwise(), currentNodeId, "OTHERWISE");
            } else {
                response.getWarnings().add(
                        "transferType '" + transferType + "' matched no branch and there is no otherwise");
            }
        }
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
}
