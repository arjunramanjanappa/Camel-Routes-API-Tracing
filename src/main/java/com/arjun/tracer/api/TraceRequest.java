package com.arjun.tracer.api;

/**
 * Inbound trace request.
 *
 * @param api          the REST API path, e.g. {@code /payment/v2/fund/submit}
 * @param version      the client release version, e.g. {@code 9.4}; blank means BASE
 * @param transferType optional choice branch filter (OWN / INTRA / INTER); blank means all branches
 * @param sourceDir    optional override of the framework source directory to analyse
 */
public record TraceRequest(String api, String version, String transferType, String sourceDir) {
}
