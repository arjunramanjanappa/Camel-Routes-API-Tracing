package com.arjun.tracer.model;

import java.util.List;

/**
 * A {@code <when>} clause inside a {@code <choice>}.
 *
 * @param predicate the raw predicate text (used to match a {@code transferType})
 * @param children  the steps executed when the predicate matches
 */
public record WhenElement(String predicate, List<RouteElement> children) implements RouteElement {
}
