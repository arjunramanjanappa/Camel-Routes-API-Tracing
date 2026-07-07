package com.arjun.tracer.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The impact catalog: every API's footprint (routes/backends/hosts) for a given
 * client version + country, plus the distinct routes/backends/hosts that can be
 * selected as "what changed". The UI intersects a selected change against each
 * API's footprint to find the impacted APIs and generate Splunk queries.
 */
public class ImpactIndex {

    private String version;
    private String country;
    private final List<ApiImpact> apis = new ArrayList<>();
    private final List<String> allRoutes = new ArrayList<>();
    private final List<String> allBackends = new ArrayList<>();
    private final List<String> allHosts = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Imports/routes that could not be resolved and need a human to review (see TraceResponse). */
    private final List<String> needsReview = new ArrayList<>();
    /** Route id → the backend APIs that route calls directly (for forward route→backend selection). */
    private final Map<String, List<String>> routeBackends = new LinkedHashMap<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public List<ApiImpact> getApis() { return apis; }
    public List<String> getAllRoutes() { return allRoutes; }
    public List<String> getAllBackends() { return allBackends; }
    public List<String> getAllHosts() { return allHosts; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getNeedsReview() { return needsReview; }
    public Map<String, List<String>> getRouteBackends() { return routeBackends; }
}
