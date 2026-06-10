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
        if (route == null) {
            if (firstVisit) {
                response.getWarnings().add("Route not found in source: " + endpoint);
            }
            attach(inherited, nodeId);                       // still record the inherited backend(s)
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
            attach(collected, nodeId);
            return nodeId;
        }
        if (firstVisit) {
            response.getFlow().add(identity);
            List<PendingApi> active = new ArrayList<>(inherited);
            // Leftover = api set in/inherited by this route that no downstream route
            // consumed → this route is itself the consumer.
            attach(walk(route.elements(), nodeId, null, active, true), nodeId);
        } else {
            attach(inherited, nodeId);                       // revisit: record inherited here (deduped)
        }
        return nodeId;
    }

    private void attach(List<PendingApi> apis, String nodeId) {
        for (PendingApi p : apis) {
            addBackend(p.value(), nodeId, p.branch());
        }
    }

    /**
     * Collect {@code setProperty name="api"} values within a host route WITHOUT
     * traversing its routing — a host's internal logic (its choice on the URI
     * protocol / camelHttpUri) is not expanded onto the graph.
     */
    private List<PendingApi> collectApis(List<RouteElement> elements, String branch) {
        List<PendingApi> out = new ArrayList<>();
        for (RouteElement el : elements) {
            if (el instanceof SetPropertyElement sp) {
                if (sp.name() != null && sp.name().equalsIgnoreCase("api")
                        && sp.value() != null && !sp.value().isBlank()) {
                    out.add(new PendingApi(sp.value().trim(), branch));
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
                handleTo(to.uri(), currentNodeId, branch, active, forward);
            } else if (el instanceof RecipientListElement rl) {
                handleRecipient(rl.expression(), currentNodeId, branch);
            } else if (el instanceof SetPropertyElement sp) {
                if (sp.name() != null && sp.name().equalsIgnoreCase("api")
                        && sp.value() != null && !sp.value().isBlank()) {
                    active.add(new PendingApi(sp.value().trim(), branch));
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
                response.getWarnings().add("Unresolved dynamic target: " + uri);
                return;
            }
            // Flag async (seda/vm) calls on the edge so they read as fire-and-forget.
            String edgeLabel = async
                    ? (branch != null && !branch.isBlank() ? branch + " · async" : "async")
                    : branch;
            if (forward) {
                visitRoute(target, currentNodeId, edgeLabel, new ArrayList<>(active));
                active.clear();                              // handed off downstream
            } else {
                visitRoute(target, currentNodeId, edgeLabel, new ArrayList<>());
            }
        } else if (EXTERNAL_SCHEMES.contains(scheme)) {
            addBackend(uri, currentNodeId, branch); // external call is itself a backend
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
            response.getWarnings().add("Dynamic recipientList not resolved: " + expression);
        }
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
        return walk(elements, currentNodeId, branch, new ArrayList<>(), forward);
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
