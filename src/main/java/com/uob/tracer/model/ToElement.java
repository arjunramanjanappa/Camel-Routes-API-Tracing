package com.uob.tracer.model;

/**
 * A {@code <to>} or {@code <toD>} step. The {@code uri} is the raw endpoint uri,
 * e.g. {@code direct:R9.4_masterFundTransferSubmitApi} or {@code http://host/x}.
 */
public record ToElement(String uri) implements RouteElement {
}
