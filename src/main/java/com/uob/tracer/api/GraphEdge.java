package com.uob.tracer.api;

/**
 * A directed edge in the trace graph.
 *
 * @param from  source node id
 * @param to    target node id
 * @param label optional edge label (e.g. a choice branch such as {@code INTER}); may be null
 */
public record GraphEdge(String from, String to, String label) {

    public GraphEdge(String from, String to) {
        this(from, to, null);
    }
}
