package com.arjun.tracer.api;

import java.util.ArrayList;
import java.util.List;

/**
 * The full trace result returned to the UI / API caller.
 */
public class TraceResponse {

    private String api;
    private String requestedVersion;
    private String transferType;
    /** Selected country scope, or null for all countries. */
    private String country;
    /** Bootstrap scopes available in the source tree (for the UI dropdown). */
    private java.util.List<String> availableCountries = new java.util.ArrayList<>();

    /** Operation name resolved from the controller, e.g. {@code fundTransferSubmitV2Api}. */
    private String operationName;
    /** Command bound to the operation, if discoverable, e.g. {@code FundTransferSubmitV2ApiCommand}. */
    private String command;

    /** The version actually used after fallback (e.g. {@code 9.3}); null for BASE. */
    private String resolvedVersion;
    /** Final route name, e.g. {@code R9.4_fundTransferSubmitV2Api} or BASE {@code fundTransferSubmitV2Api}. */
    private String resolvedRoute;
    /** True when no R-version route matched and the BASE route was used. */
    private boolean baseFallback;

    /** Ordered list of route names visited (depth-first), for a quick textual flow. */
    private final List<String> flow = new ArrayList<>();
    /** Distinct backend API endpoints discovered (values of {@code setProperty name="api"}). */
    private final List<String> backendApis = new ArrayList<>();
    /** Backend URL → service version number, read from the preceding framework template. */
    private final java.util.Map<String, String> backendVersions = new java.util.LinkedHashMap<>();
    /** Backend api value → its "hosturl" property (the path the host logs in MightyHostMessage). */
    private final java.util.Map<String, String> backendHosturls = new java.util.LinkedHashMap<>();
    /** Non-fatal notes: loader fallbacks, unresolved direct: targets, etc. */
    private final List<String> warnings = new ArrayList<>();

    private RouteGraph graph;

    // --- getters / setters ---

    /** Discriminator for the UI: a single-API trace. */
    public String getMode() { return "single"; }

    public String getApi() { return api; }
    public void setApi(String api) { this.api = api; }

    public String getRequestedVersion() { return requestedVersion; }
    public void setRequestedVersion(String requestedVersion) { this.requestedVersion = requestedVersion; }

    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public java.util.List<String> getAvailableCountries() { return availableCountries; }
    public void setAvailableCountries(java.util.List<String> availableCountries) { this.availableCountries = availableCountries; }

    public String getOperationName() { return operationName; }
    public void setOperationName(String operationName) { this.operationName = operationName; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getResolvedVersion() { return resolvedVersion; }
    public void setResolvedVersion(String resolvedVersion) { this.resolvedVersion = resolvedVersion; }

    public String getResolvedRoute() { return resolvedRoute; }
    public void setResolvedRoute(String resolvedRoute) { this.resolvedRoute = resolvedRoute; }

    public boolean isBaseFallback() { return baseFallback; }
    public void setBaseFallback(boolean baseFallback) { this.baseFallback = baseFallback; }

    public List<String> getFlow() { return flow; }
    public List<String> getBackendApis() { return backendApis; }
    public java.util.Map<String, String> getBackendVersions() { return backendVersions; }
    public java.util.Map<String, String> getBackendHosturls() { return backendHosturls; }
    public List<String> getWarnings() { return warnings; }

    public RouteGraph getGraph() { return graph; }
    public void setGraph(RouteGraph graph) { this.graph = graph; }
}
