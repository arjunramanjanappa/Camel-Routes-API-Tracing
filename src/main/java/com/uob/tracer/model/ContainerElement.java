package com.uob.tracer.model;

import java.util.List;

/**
 * A generic container for processor kinds the tracer does not model explicitly
 * (split, multicast, loop, doTry, filter, ...). Its children are still
 * traversed so nested {@code to}/{@code setProperty}/{@code choice} steps are
 * not lost.
 *
 * @param kind     the underlying processor kind (informational)
 * @param children the nested steps
 */
public record ContainerElement(String kind, List<RouteElement> children) implements RouteElement {
}
