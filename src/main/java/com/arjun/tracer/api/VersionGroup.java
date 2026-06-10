package com.arjun.tracer.api;

import java.util.List;

/**
 * A catalog group: all API traces that resolve to a given client release
 * version ({@code "9.4"}, {@code "9.3"}, {@code "BASE"}, or
 * {@code "(no route found)"}).
 *
 * @param version the group key
 * @param traces  one entry per API in this group (each entry's own graph is
 *                omitted; the combined graph lives on {@link CatalogResponse})
 */
public record VersionGroup(String version, List<TraceResponse> traces) {
}
