package com.arjun.tracer.model;

import java.util.List;

/**
 * A {@code <choice>} step with its {@code <when>} clauses and optional
 * {@code <otherwise>} branch. Drives OWN / INTRA / INTER branching.
 */
public record ChoiceElement(List<WhenElement> whens, List<RouteElement> otherwise) implements RouteElement {
}
