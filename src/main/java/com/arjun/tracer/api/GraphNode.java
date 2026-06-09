package com.arjun.tracer.api;

/**
 * A node in the trace graph.
 *
 * @param id    stable unique identifier (e.g. {@code route:R9.4_fundTransferSubmitV2Api})
 * @param label human friendly label rendered in the UI
 * @param type  one of {@code API}, {@code ROUTE}, {@code BACKEND}
 */
public record GraphNode(String id, String label, String type) {

    public static final String TYPE_API = "API";
    public static final String TYPE_ROUTE = "ROUTE";
    public static final String TYPE_BACKEND = "BACKEND";
}
