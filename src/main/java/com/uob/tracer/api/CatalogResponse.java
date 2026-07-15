package com.uob.tracer.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of catalog mode (no API supplied): every discovered API, grouped by the
 * client release version its route resolves to, plus one combined graph.
 */
public class CatalogResponse {

    private String requestedVersion;
    private String transferType;
    private String country;
    /** The module's name (its pom.xml artifactId, else the source folder) — for grouping multi-module analyses. */
    private String moduleName;
    /** True when the source has no versioned (R&lt;ver&gt;_) routes, so it was analysed at N/A (latest). */
    private boolean unversioned;
    private final List<String> availableCountries = new ArrayList<>();
    private int operationCount;

    /** Distinct version keys present, in display order (highest first, BASE last). */
    private final List<String> versionsFound = new ArrayList<>();
    private final List<VersionGroup> groups = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Imports/routes that could not be resolved and need a human to review (see TraceResponse). */
    private final List<String> needsReview = new ArrayList<>();

    private RouteGraph graph;

    /** Discriminator for the UI: a grouped catalog of all APIs. */
    public String getMode() { return "catalog"; }

    public String getRequestedVersion() { return requestedVersion; }
    public void setRequestedVersion(String requestedVersion) { this.requestedVersion = requestedVersion; }

    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    public boolean isUnversioned() { return unversioned; }
    public void setUnversioned(boolean unversioned) { this.unversioned = unversioned; }

    public List<String> getAvailableCountries() { return availableCountries; }

    public int getOperationCount() { return operationCount; }
    public void setOperationCount(int operationCount) { this.operationCount = operationCount; }

    public List<String> getVersionsFound() { return versionsFound; }
    public List<VersionGroup> getGroups() { return groups; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getNeedsReview() { return needsReview; }

    public RouteGraph getGraph() { return graph; }
    public void setGraph(RouteGraph graph) { this.graph = graph; }
}
