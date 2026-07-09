package com.uob.tracer.model;

/**
 * Marker for a node in a route's processor tree.
 *
 * <p>The tracer only models the processor kinds that matter for flow
 * reconstruction; everything else is represented as a {@link ContainerElement}
 * so traversal can still descend into its children.
 */
public interface RouteElement {
}
