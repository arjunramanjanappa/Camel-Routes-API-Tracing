package com.arjun.tracer.api;

import java.util.Map;

/**
 * A node in the trace graph.
 *
 * @param id    stable unique identifier (e.g. {@code route:R9.4_fundTransferSubmitV2Api})
 * @param label human friendly label rendered in the UI
 * @param type  one of {@code API}, {@code ROUTE}, {@code BACKEND}
 * @param data  optional extra attributes for the UI (e.g. {@code source} = which
 *              loader produced a route); may be null
 */
public record GraphNode(String id, String label, String type, Map<String, Object> data) {

    public static final String TYPE_API = "API";
    public static final String TYPE_ROUTE = "ROUTE";
    public static final String TYPE_BACKEND = "BACKEND";

    public GraphNode(String id, String label, String type) {
        this(id, label, type, null);
    }
}
