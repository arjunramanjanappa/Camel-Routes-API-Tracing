package com.uob.tracer.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cytoscape-friendly graph payload: a de-duplicated set of nodes and edges.
 */
public class RouteGraph {

    /** Keyed by node id so a route/backend referenced many times appears once. */
    private final Map<String, GraphNode> nodeIndex = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    public void addNode(GraphNode node) {
        nodeIndex.putIfAbsent(node.id(), node);
    }

    /**
     * Set a backend node's service version. A backend URL can be called with several
     * versions (different branches/templates), so the caller passes the accumulated
     * value (e.g. {@code "2.2 / 3.3"}) and this replaces the node's data.
     */
    public void setBackendServiceVersion(String id, String serviceVersion) {
        GraphNode n = nodeIndex.get(id);
        if (n == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (n.data() != null) {
            data.putAll(n.data());
        }
        data.put("serviceVersion", serviceVersion);
        nodeIndex.put(id, new GraphNode(n.id(), n.label(), n.type(), data));
    }

    public boolean hasNode(String id) {
        return nodeIndex.containsKey(id);
    }

    public void addEdge(String from, String to, String label) {
        // Avoid emitting an identical edge twice.
        for (GraphEdge e : edges) {
            if (e.from().equals(from) && e.to().equals(to)
                    && java.util.Objects.equals(e.label(), label)) {
                return;
            }
        }
        edges.add(new GraphEdge(from, to, label));
    }

    public List<GraphNode> getNodes() {
        return new ArrayList<>(nodeIndex.values());
    }

    public List<GraphEdge> getEdges() {
        return edges;
    }
}
