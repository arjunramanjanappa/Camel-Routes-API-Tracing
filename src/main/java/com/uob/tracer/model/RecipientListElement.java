package com.uob.tracer.model;

/**
 * A {@code <recipientList>} step. {@code expression} is the raw recipient
 * expression text, e.g. {@code direct:${exchangeProperty[operationName]}}.
 */
public record RecipientListElement(String expression) implements RouteElement {
}
