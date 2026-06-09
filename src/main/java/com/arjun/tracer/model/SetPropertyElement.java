package com.arjun.tracer.model;

/**
 * A {@code <setProperty>} step. The tracer cares about {@code name="api"}
 * (and {@code hostUrl}) whose {@code value} identifies a backend API call.
 */
public record SetPropertyElement(String name, String value) implements RouteElement {
}
