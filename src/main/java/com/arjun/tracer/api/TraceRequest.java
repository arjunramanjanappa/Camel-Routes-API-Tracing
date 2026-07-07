package com.arjun.tracer.api;

import java.util.List;

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
 * @param dependencies optional extra source roots that provide XMLs the primary source
 *                     {@code <import>}s but doesn't contain (routes packaged in a shared
 *                     library). Each entry is encoded as {@code local:<path>} or
 *                     {@code bit:<repoUrl>|<branch>}; scanned and merged so those imports
 *                     resolve. Empty means primary source only.
 */
public record TraceRequest(String api, String version, String transferType,
                           String sourceDir, String country, String repo, String branch,
                           List<String> dependencies) {

    /** Normalise a null dependency list to an immutable empty one. */
    public TraceRequest {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** Bitbucket-source constructor without dependencies. */
    public TraceRequest(String api, String version, String transferType,
                        String sourceDir, String country, String repo, String branch) {
        this(api, version, transferType, sourceDir, country, repo, branch, List.of());
    }

    /** Backwards-compatible constructor with a country scope but no Bitbucket source. */
    public TraceRequest(String api, String version, String transferType, String sourceDir, String country) {
        this(api, version, transferType, sourceDir, country, null, null, List.of());
    }

    /** Backwards-compatible constructor without a country scope. */
    public TraceRequest(String api, String version, String transferType, String sourceDir) {
        this(api, version, transferType, sourceDir, null, null, null, List.of());
    }
}
