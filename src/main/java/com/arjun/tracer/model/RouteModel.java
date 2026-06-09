package com.arjun.tracer.model;

import java.util.List;

/**
 * Source-agnostic representation of a single Camel route, produced by either
 * the Camel {@code RouteDefinition} loader or the DOM fallback loader.
 *
 * @param routeId  the route id attribute, e.g. {@code R9.4_fundTransferSubmitV2Api}
 * @param fromUri  the {@code <from>} uri, e.g. {@code direct:R9.4_fundTransferSubmitV2Api}
 * @param elements the ordered processor steps
 * @param source   {@code "camel"} or {@code "dom"} — which loader produced it
 */
public record RouteModel(String routeId, String fromUri, List<RouteElement> elements, String source) {

    /** The endpoint name without the {@code direct:} (or other scheme) prefix. */
    public String fromName() {
        if (fromUri == null) {
            return null;
        }
        int colon = fromUri.indexOf(':');
        return colon >= 0 ? fromUri.substring(colon + 1) : fromUri;
    }
}
