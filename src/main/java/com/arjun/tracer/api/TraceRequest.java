package com.arjun.tracer.api;

/**
 * Inbound trace request.
 *
 * @param api          the REST API path, e.g. {@code /payment/v2/fund/submit}
 * @param version      the client release version, e.g. {@code 9.4}; blank means BASE
 * @param transferType optional choice branch filter (OWN / INTRA / INTER); blank means all branches
 * @param sourceDir    the framework source directory to analyse (local-path mode)
 * @param country      optional bootstrap scope (e.g. {@code SG}); blank means all countries
 * @param repo         Bitbucket HTTPS repo URL — when non-blank the server clones it and analyses
 *                     the checkout instead of {@code sourceDir} (Bitbucket-branch mode)
 * @param branch       the branch or tag to check out (only used with {@code repo})
 */
public record TraceRequest(String api, String version, String transferType,
                           String sourceDir, String country, String repo, String branch) {

    /** Backwards-compatible constructor with a country scope but no Bitbucket source. */
    public TraceRequest(String api, String version, String transferType, String sourceDir, String country) {
        this(api, version, transferType, sourceDir, country, null, null);
    }

    /** Backwards-compatible constructor without a country scope. */
    public TraceRequest(String api, String version, String transferType, String sourceDir) {
        this(api, version, transferType, sourceDir, null, null, null);
    }
}
