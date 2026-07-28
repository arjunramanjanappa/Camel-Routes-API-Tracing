package com.uob.tracer.trace;

/**
 * The service version a request-body template contributes.
 *
 * @param version      the numeric {@code serviceVersionNumber} literal read from the template — for a
 *                     header-driven template this is only the fallback (e.g. the {@code #else} default)
 * @param headerDriven true if the template emits {@code serviceVersionNumber} from
 *                     {@code ${headers.serviceVersionNumber}} — then a route-level
 *                     {@code <setHeader name="serviceVersionNumber">} value is what actually gets sent,
 *                     and it wins over {@code version}
 */
public record TemplateSvc(String version, boolean headerDriven) {
}
