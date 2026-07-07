package com.arjun.tracer.service;

import com.arjun.tracer.api.ApiDiff;
import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.BackendVersionChange;
import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.GraphEdge;
import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.ImpactIndex;
import com.arjun.tracer.api.PayloadChange;
import com.arjun.tracer.api.RouteGraph;
import com.arjun.tracer.api.RouteStepDiff;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.api.VersionDiffReport;
import com.arjun.tracer.api.VersionGroup;
import com.arjun.tracer.loader.RouteRegistry;
import com.arjun.tracer.resolve.OperationInfo;
import com.arjun.tracer.resolve.OperationResolver;
import com.arjun.tracer.resolve.VersionResolver;
import com.arjun.tracer.resolve.VersionResolver.ResolvedRoute;
import com.arjun.tracer.scan.SourceIndex;
import com.arjun.tracer.scan.SourceScanner;
import com.arjun.tracer.trace.RouteTraverser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates analysis. Input modes:
 *
 * <ul>
 *   <li><b>api + version</b> → single trace of that exact resolution;</li>
 *   <li><b>api only</b> → single trace (blank version resolves to BASE);</li>
 *   <li><b>no api</b> → catalog: every discovered API, grouped by client release version.</li>
 * </ul>
 *
 * <p>Any mode may be scoped to a <b>country</b> bootstrap (e.g. {@code SG}): only
 * the routes that bootstrap pulls in (via {@code <import>}/{@code <routeContextRef>})
 * are considered.
 */
@Service
public class RouteTraceService {

    private static final String BASE_GROUP = "BASE";
    private static final String NO_ROUTE_GROUP = "(no route found)";

    private final String defaultSourceDir;
    private final SourceScanner scanner = new SourceScanner();
    private final VersionResolver versionResolver = new VersionResolver();
    // Scanning + parsing a large framework is expensive and the same source dir is hit
    // repeatedly (meta on every keystroke, then trace, then impact, then log analysis).
    // Cache the scan per source dir for the JVM lifetime — restart the app to pick up
    // source changes.
    private final java.util.Map<String, SourceIndex> scanCache = new java.util.concurrent.ConcurrentHashMap<>();
    // The impact index traces every API, so it's cached per (source dir, country, version,
    // branch): the Load button, re-loads, and the log-analysis upload all reuse it.
    private final java.util.Map<String, ImpactIndex> impactCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Release-diff report, cached per (source dir, country, target version): the Load
    // button and re-loads reuse it. Plus the raw route-XML bodies indexed per source dir.
    private final java.util.Map<String, VersionDiffReport> versionDiffCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Map<String, List<String>>> routeBodyCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Route id -> source file + line range, per source dir, for git-blame attribution of changed routes.
    private final java.util.Map<String, Map<String, RouteXmlDiff.RouteLocation>> routeLocationCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Last-seen source fingerprint per root. Each Load/Compare/Trace re-checks it and keeps
    // the warm caches when nothing changed (the common case: repeated searches, tab switches,
    // log-analysis reusing the index), rebuilding only when the tree actually changed on disk.
    private final java.util.Map<String, Long> sourceFingerprints = new java.util.concurrent.ConcurrentHashMap<>();
    private final GitBlameService gitBlame = new GitBlameService();
    // Resolves a "Bitbucket branch" source to a local checkout; unused in local-path mode.
    private final SourceResolver sourceResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public RouteTraceService(@Value("${tracer.source-dir:}") String defaultSourceDir, SourceResolver sourceResolver) {
        this.defaultSourceDir = defaultSourceDir;
        this.sourceResolver = sourceResolver;
    }

    /** Local-only constructor (tests / direct use) — Bitbucket source not configured. */
    public RouteTraceService(String defaultSourceDir) {
        this(defaultSourceDir, new SourceResolver(""));
    }

    /** A primary source root plus any dependency roots that supply imported (shared-library) XMLs. */
    private record Roots(Path primary, List<Path> deps, List<String> depErrors) {
        /** A cache key that changes when the primary OR the dependency set changes. */
        String key() {
            StringBuilder sb = new StringBuilder(primary.toAbsolutePath().normalize().toString());
            for (Path d : deps) {
                sb.append(' ').append(d.toAbsolutePath().normalize().toString());
            }
            return sb.toString();
        }
    }

    /** The scan for a primary+dependency root set, computed once and reused (see {@link #scanCache}). */
    private SourceIndex scanCached(Roots roots) {
        return scanCache.computeIfAbsent(roots.key(), k -> scanner.scan(roots.primary(), roots.deps()));
    }

    /**
     * Called at the start of each Load / Compare / Trace. Keeps this root's warm caches when
     * the source tree is unchanged since the last call — the common case (repeated searches,
     * tab switches, the log-analysis upload reusing the impact index), so those stay instant —
     * and drops them only when a file actually changed on disk, so edits are picked up on the
     * next reload without restarting the app or refreshing the browser.
     *
     * <p>The fingerprint only stats files (no parsing), so the check is cheap next to a rebuild.
     */
    private void invalidateIfChanged(Roots roots) {
        String key = roots.key();
        long fp = scanner.fingerprint(roots.primary());
        for (Path d : roots.deps()) {
            fp = fp * 31 + scanner.fingerprint(d);   // a changed dependency also drops the caches
        }
        Long prev = sourceFingerprints.put(key, fp);
        if (prev != null && prev == fp) {
            return;   // unchanged — keep the caches warm
        }
        scanCache.remove(key);
        routeBodyCache.remove(key);
        routeLocationCache.remove(key);
        impactCache.keySet().removeIf(k -> k.equals(key) || k.startsWith(key + "|"));
        versionDiffCache.keySet().removeIf(k -> k.equals(key) || k.startsWith(key + "|"));
    }

    /** Scan result plus the registry chosen for this request's country scope. */
    private record Prepared(SourceIndex index, RouteRegistry registry, List<String> warnings, String country) {
    }

    /** Entry point used by the controller: returns a {@link TraceResponse} or {@link CatalogResponse}. */
    public Object analyze(TraceRequest request) {
        try {
            invalidateIfChanged(resolveRoots(request));   // pick up source edits since the last trace
        } catch (RuntimeException ignore) {
            // no source dir configured — let prepare() surface the original error
        }
        Prepared prepared = prepare(request);
        boolean hasApi = request.api() != null && !request.api().isBlank();
        return hasApi ? single(request, prepared) : catalog(request, prepared);
    }

    /** Single-API trace (kept for direct use / tests). */
    public TraceResponse trace(TraceRequest request) {
        return single(request, prepare(request));
    }

    /** The bootstrap scopes (countries) available in the source tree. */
    public List<String> listCountries(TraceRequest request) {
        return scanCached(resolveRoots(request)).countries();
    }

    /**
     * Discovery metadata for the UI: available countries, release versions and
     * branch ({@code transferType}) values. Versions/branches honour the country
     * scope when one is given.
     */
    public Map<String, Object> meta(TraceRequest request) {
        SourceIndex index = scanCached(resolveRoots(request));
        String country = (request.country() != null && !request.country().isBlank())
                ? request.country().trim() : null;
        RouteRegistry registry = country != null
                ? index.scopedRegistry(country, new ArrayList<>())
                : index.fullRegistry();
        List<String> versions = registry.allVersions();
        versions.sort((a, b) -> compareVersions(b, a));
        return Map.of(
                "countries", index.countries(),
                "versions", versions,
                "transferTypes", registry.allBranchValues());
    }

    /**
     * Impact catalog: every API's footprint (routes/backends/hosts) at the given
     * client version + country, scanned once. The UI intersects a selected change
     * (changed routes/backends) against each footprint to find impacted APIs.
     *
     * <p>Cached per (source dir, country, version, branch): building it traces every
     * API, so the Load button, a re-load, and the log-analysis upload (which rebuilds
     * the same index) all reuse one computation. Restart the app to pick up source
     * changes.
     */
    public ImpactIndex impactIndex(TraceRequest request) {
        String key;
        Roots roots;
        try {
            roots = resolveRoots(request);
            key = roots.key()
                    + "|" + nz(request.country()) + "|" + nz(request.version()) + "|" + nz(request.transferType());
        } catch (RuntimeException e) {
            return computeImpactIndex(request);   // no source dir → preserve the original error path
        }
        invalidateIfChanged(roots);   // reuse the warm cache unless the sources changed
        return impactCache.computeIfAbsent(key, k -> computeImpactIndex(request));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private ImpactIndex computeImpactIndex(TraceRequest request) {
        Prepared prepared = prepare(request);
        ImpactIndex out = new ImpactIndex();
        out.setVersion(request.version());
        out.setCountry(prepared.country());
        out.getWarnings().addAll(prepared.warnings());

        Set<String> routes = new TreeSet<>();
        Set<String> backends = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        Map<String, Set<String>> routeBackends = new TreeMap<>();

        // With a client release version, the impact set is ONLY the APIs that
        // release actually changed — i.e. whose entry route resolves to exactly
        // that version. APIs that fall back to a lower version or BASE are not
        // impacted and are excluded, so the APIs / routes / backends shown are
        // scoped to the release (e.g. only R9.18_* routes), not the whole repo.
        boolean versionGiven = request.version() != null && !request.version().isBlank();
        String wantedVersion = versionGiven ? request.version().trim() : null;
        int excluded = 0;
        var templateVersion = templateVersionResolver(request);
        List<String> harvested = new ArrayList<>();

        for (OperationInfo op : operationsInScope(prepared)) {
            ResolvedRoute resolved =
                    versionResolver.resolve(prepared.registry(), op.operationName(), request.version());
            if (wantedVersion != null && !wantedVersion.equals(resolved.version())) {
                excluded++;
                continue;   // resolves to a lower version or BASE — not impacted by this release
            }
            TraceResponse r = new TraceResponse();
            RouteGraph graph = new RouteGraph();
            traverseInto(r, op.path(), op.operationName(), resolved,
                    request.transferType(), prepared.registry(), graph, templateVersion, request.version());
            harvested.addAll(r.getWarnings());   // "Route not found in source" for dependency routes not yet added
            List<String> apiHosts = extractHosts(graph);

            // Shared call routes (CamelHttpUri hosts AND per-call terminal routes such
            // as callUFWDGE, drawn as route:<id>#N instances) are reused by every
            // version and every API, so marking one "changed" would falsely impact
            // everything. Drop them from the route footprint — the backend APIs they
            // call still carry the real, per-API impact signal.
            Set<String> shared = sharedCallRoutes(graph);
            List<String> businessRoutes = r.getFlow().stream()
                    .filter(routeName -> !shared.contains(routeName))
                    .toList();

            out.getApis().add(new ApiImpact(
                    op.path(), op.operationName(), op.command(),
                    resolved.routeName(), resolved.version(), resolved.baseFallback(),
                    businessRoutes, List.copyOf(r.getBackendApis()), apiHosts,
                    Map.copyOf(r.getBackendVersions()), Map.copyOf(r.getBackendHosturls())));

            routes.addAll(businessRoutes);
            backends.addAll(r.getBackendApis());
            hosts.addAll(apiHosts);
            collectRouteBackends(graph, new HashSet<>(businessRoutes), routeBackends);
        }
        out.getAllRoutes().addAll(routes);
        out.getAllBackends().addAll(backends);
        out.getAllHosts().addAll(hosts);
        routeBackends.forEach((route, bes) -> out.getRouteBackends().put(route, new ArrayList<>(bes)));
        if (excluded > 0) {
            out.getWarnings().add(excluded + " API(s) are not impacted by version " + wantedVersion
                    + " (they resolve to a lower version or BASE) and were excluded.");
        }
        applyReview(out.getWarnings(), out.getNeedsReview(), harvested);
        return out;
    }

    // --- mode: release diff ---

    private static final Pattern VERSIONED_ID = Pattern.compile("^R(\\d+(?:\\.\\d+)*)_(.+)$");

    /**
     * Release-diff report: for a target client version, what each impacted API
     * changed relative to its <em>immediate-lower</em> version. For every API whose
     * entry route resolves to exactly the target version, the whole resolved flow is
     * traced at the target and again at the immediate-lower version (the highest
     * versioned route below the target, else BASE, else the API is NEW); the routes
     * are paired by base name and their raw XML bodies are structurally diffed.
     *
     * <p>Read-only and additive — it reuses the cached scan, the version resolver and
     * the existing traversal without touching trace / impact behaviour. Cached per
     * (source dir, country, target version).
     */
    public VersionDiffReport versionDiff(TraceRequest request) {
        String key;
        Roots roots;
        try {
            roots = resolveRoots(request);
            key = roots.key()
                    + "|" + nz(request.country()) + "|" + nz(request.version());
        } catch (RuntimeException e) {
            return computeVersionDiff(request);   // no source dir → preserve the original error path
        }
        invalidateIfChanged(roots);   // reuse the warm cache unless the sources changed
        return versionDiffCache.computeIfAbsent(key, k -> computeVersionDiff(request));
    }

    private VersionDiffReport computeVersionDiff(TraceRequest request) {
        Prepared prepared = prepare(request);
        VersionDiffReport report = new VersionDiffReport();
        report.setCountry(prepared.country());
        report.getWarnings().addAll(prepared.warnings());
        List<String> harvested = new ArrayList<>();

        String target = nz(request.version());
        report.setVersion(target);
        if (target.isEmpty()) {
            report.getWarnings().add("Select a target version to compare (e.g. 9.18).");
            applyReview(report.getWarnings(), report.getNeedsReview(), harvested);
            return report;
        }

        Roots roots = resolveRoots(request);
        Map<String, List<String>> bodies = routeBodiesCached(roots);
        Map<String, RouteXmlDiff.RouteLocation> locations = routeLocationsCached(roots);
        var templateVersion = templateVersionResolver(request);
        var templateKeys = templateKeysResolver(request);
        RouteRegistry registry = prepared.registry();

        int changed = 0;
        int added = 0;
        int unchanged = 0;
        for (OperationInfo op : operationsInScope(prepared)) {
            ResolvedRoute targetResolved = versionResolver.resolve(registry, op.operationName(), target);
            if (!target.equals(targetResolved.version())) {
                // No route at the target version. If the API still resolves to a REAL
                // lower route (a versioned one, or a BASE route), a client on the target
                // release uses that — the release left this API untouched, so surface it
                // as UNCHANGED (behind the toggle) with a note. If there's no route at all
                // for the API, there is nothing to show — skip it.
                boolean hasRealResolution = targetResolved.version() != null
                        || registry.contains(op.operationName());
                if (!hasRealResolution) {
                    continue;
                }
                String resolvedLabel = targetResolved.version() != null ? targetResolved.version() : BASE_GROUP;
                report.getApis().add(new ApiDiff(op.path(), op.operationName(),
                        targetResolved.routeName(), resolvedLabel, null, null,
                        ApiDiff.UNCHANGED, List.of(), List.of(), List.of(), List.of(), null,
                        "No " + target + " route — a " + target + " client still resolves to "
                                + targetResolved.routeName() + ".", List.of()));
                unchanged++;
                continue;
            }
            TraceResponse targetTrace = traceFor(prepared, op, targetResolved, templateVersion);
            harvested.addAll(targetTrace.getWarnings());

            // Immediate-lower baseline: highest versioned route below target, else BASE, else NEW.
            String lowerVer = versionResolver.immediateLowerVersion(registry, op.operationName(), target);
            ResolvedRoute lowerResolved;
            String lowerLabel;
            if (lowerVer != null) {
                lowerResolved = new ResolvedRoute("R" + lowerVer + "_" + op.operationName(), lowerVer, false);
                lowerLabel = lowerVer;
            } else if (registry.contains(op.operationName())) {
                lowerResolved = new ResolvedRoute(op.operationName(), null, true);   // BASE baseline
                lowerLabel = BASE_GROUP;
            } else {
                // New API: blame the new release's routes in its flow to attribute who added it.
                List<String> addedBy = flowAuthors(targetTrace.getFlow(), target, locations);
                report.getApis().add(new ApiDiff(op.path(), op.operationName(),
                        targetResolved.routeName(), target, null, null,
                        ApiDiff.NEW, List.of(), List.of(), List.of(), List.of(), null, null, addedBy));
                added++;
                continue;
            }

            TraceResponse lowerTrace = traceFor(prepared, op, lowerResolved, templateVersion);
            // Deliberately NOT harvesting lowerTrace warnings into needs-review: the lower version is
            // the in-production BAU flow, traced only for the diff. A dynamic route that is new in the
            // target legitimately doesn't resolve at the lower version, so flagging it here is a false
            // alarm — only the target flow's gaps are actionable for THIS release.
            ApiDiff diff = buildApiDiff(op, targetResolved, target, lowerResolved, lowerLabel,
                    targetTrace, lowerTrace, bodies, locations, templateKeys);
            report.getApis().add(diff);
            if (ApiDiff.CHANGED.equals(diff.status())) {
                changed++;
            } else {
                unchanged++;
            }
        }

        report.setChangedCount(changed);
        report.setNewCount(added);
        report.setUnchangedCount(unchanged);
        if (changed + added + unchanged == 0) {
            report.getWarnings().add("No API resolves to version " + target
                    + " in this scope — nothing was introduced or changed by this release here.");
        }
        applyReview(report.getWarnings(), report.getNeedsReview(), harvested);
        // Changed first, then new, then version-bumped-no-change; alphabetical within each.
        report.getApis().sort(Comparator
                .comparingInt((ApiDiff a) -> statusRank(a.status()))
                .thenComparing(ApiDiff::api));
        return report;
    }

    private static int statusRank(String status) {
        if (ApiDiff.CHANGED.equals(status)) {
            return 0;
        }
        return ApiDiff.NEW.equals(status) ? 1 : 2;
    }

    /** Trace one API at a resolved entry; the response carries the flow + backend versions. */
    private TraceResponse traceFor(Prepared prepared, OperationInfo op, ResolvedRoute resolved,
                                   java.util.function.Function<String, String> templateVersion) {
        TraceResponse r = new TraceResponse();
        RouteGraph graph = new RouteGraph();
        // transferType left null on purpose: walk every branch so the flow includes
        // all sub-routes either version could reach, for a complete structural compare.
        // clientVersion = the version this side of the diff is simulating (target or lower), so a
        // dynamic DEST_ROUTE toD follows that version's route on each side.
        traverseInto(r, op.path(), op.operationName(), resolved, null,
                prepared.registry(), graph, templateVersion, resolved.version());
        return r;
    }

    private ApiDiff buildApiDiff(OperationInfo op, ResolvedRoute targetResolved, String target,
                                 ResolvedRoute lowerResolved, String lowerLabel,
                                 TraceResponse targetTrace, TraceResponse lowerTrace,
                                 Map<String, List<String>> bodies,
                                 Map<String, RouteXmlDiff.RouteLocation> locations,
                                 java.util.function.Function<String, List<PayloadKeys.KeyRef>> templateKeys) {
        Map<String, String> targetByBase = new LinkedHashMap<>();
        for (String id : targetTrace.getFlow()) {
            targetByBase.putIfAbsent(baseName(id), id);
        }
        Map<String, String> lowerByBase = new LinkedHashMap<>();
        for (String id : lowerTrace.getFlow()) {
            lowerByBase.putIfAbsent(baseName(id), id);
        }

        List<RouteStepDiff> routeDiffs = new ArrayList<>();
        List<String> addedRoutes = new ArrayList<>();
        List<String> removedRoutes = new ArrayList<>();

        LinkedHashSet<String> bases = new LinkedHashSet<>(targetByBase.keySet());
        bases.addAll(lowerByBase.keySet());
        for (String base : bases) {
            String tId = targetByBase.get(base);
            String lId = lowerByBase.get(base);
            if (tId != null && lId == null) {
                addedRoutes.add(base);   // a sub-route the target flow calls that the lower flow did not
                continue;
            }
            if (tId == null) {
                removedRoutes.add(base); // a sub-route the lower flow called that the target flow dropped
                continue;
            }
            List<String> tBody = bodies.getOrDefault(tId, List.of());
            List<String> lBody = bodies.getOrDefault(lId, List.of());
            RouteXmlDiff.Diff d = RouteXmlDiff.diff(lBody, tBody);
            if (!d.isEmpty()) {
                // Attribute the change to whoever authored the TARGET (latest) route's
                // lines — the lower/BAU version is in production, so its authors are not
                // who made this change. Empty unless the source dir is a git work tree.
                List<String> changedBy = blameAuthors(locations.get(tId));
                routeDiffs.add(new RouteStepDiff(base, tId, lId, d.added(), d.removed(), changedBy));
            }
        }

        // Backend service-version changes (e.g. 2.2 → 2.3) live in the framework
        // request template, NOT the route XML, so the structural diff above can't see
        // them. Compare the traced backend versions of the two flows for backends
        // present in both — a bump shows as a change even if the route body is otherwise equal.
        List<BackendVersionChange> svcChanges = backendVersionChanges(
                lowerTrace.getBackendVersions(), targetTrace.getBackendVersions());

        // Payload change: the JSON keys added/removed across the request-body templates the
        // two flows use. Key-based and engine-agnostic (a .vm -> .ftl migration with the same
        // keys is no change); serviceVersionNumber is excluded (it's the svc-version bump above).
        List<PayloadKeys.KeyRef> targetKeys = new ArrayList<>();
        for (String u : targetTrace.getTemplateUris()) {
            targetKeys.addAll(templateKeys.apply(u));
        }
        List<PayloadKeys.KeyRef> lowerKeys = new ArrayList<>();
        for (String u : lowerTrace.getTemplateUris()) {
            lowerKeys.addAll(templateKeys.apply(u));
        }
        PayloadKeys.PayloadDiff pd = PayloadKeys.diff(targetKeys, lowerKeys);
        PayloadChange payloadChange = pd.isEmpty() ? null : new PayloadChange(pd.added(), pd.removed());

        boolean anyChange = !routeDiffs.isEmpty() || !addedRoutes.isEmpty()
                || !removedRoutes.isEmpty() || !svcChanges.isEmpty() || payloadChange != null;
        return new ApiDiff(op.path(), op.operationName(),
                targetResolved.routeName(), target,
                lowerResolved.routeName(), lowerLabel,
                anyChange ? ApiDiff.CHANGED : ApiDiff.UNCHANGED,
                routeDiffs, addedRoutes, removedRoutes, svcChanges, payloadChange, null, List.of());
    }

    /**
     * Backends whose resolved service version differs between the lower and target
     * flows. Only backends present in BOTH flows with two concrete, differing
     * versions are reported — a missing/unresolved version (null) is treated as
     * "unknown", not a change, to avoid false positives from template-resolution gaps.
     */
    private List<BackendVersionChange> backendVersionChanges(Map<String, String> lower,
                                                             Map<String, String> targetVersions) {
        List<BackendVersionChange> out = new ArrayList<>();
        for (Map.Entry<String, String> e : targetVersions.entrySet()) {
            String to = e.getValue();
            String from = lower.get(e.getKey());
            if (from != null && to != null && !from.equals(to)) {
                out.add(new BackendVersionChange(e.getKey(), from, to));
            }
        }
        return out;
    }

    /** The version-stripped route name: {@code R9.18_xApi} → {@code xApi}; BASE ids unchanged. */
    private static String baseName(String routeId) {
        if (routeId == null) {
            return "";
        }
        String id = routeId;
        int hash = id.indexOf('#');     // drop any per-call instance suffix the traverser adds
        if (hash >= 0) {
            id = id.substring(0, hash);
        }
        Matcher m = VERSIONED_ID.matcher(id);
        return m.matches() ? m.group(2) : id;
    }

    /** Raw route-XML bodies (route id → canonical lines) for a root set, computed once. */
    private Map<String, List<String>> routeBodiesCached(Roots roots) {
        return routeBodyCache.computeIfAbsent(roots.key(),
                k -> RouteXmlDiff.indexRouteBodies(scanCached(roots).allFiles()));
    }

    /** Route id → source file + line range for a root set, computed once. */
    private Map<String, RouteXmlDiff.RouteLocation> routeLocationsCached(Roots roots) {
        return routeLocationCache.computeIfAbsent(roots.key(),
                k -> RouteXmlDiff.indexRouteLocations(scanCached(roots).allFiles()));
    }

    /** git-blame authors of a route's lines, or empty when the location/repo is unknown. */
    private List<String> blameAuthors(RouteXmlDiff.RouteLocation loc) {
        if (loc == null) {
            return List.of();
        }
        return gitBlame.authors(loc.file(), loc.startLine(), loc.endLine());
    }

    /**
     * Distinct git-blame authors across the target release's routes in a flow — for a NEW
     * API, "who added it". Only this release's own routes ({@code R<target>_…}) are blamed;
     * shared infrastructure (e.g. callUFWDGE) is skipped so its authors don't dilute the list.
     */
    private List<String> flowAuthors(List<String> flow, String target, Map<String, RouteXmlDiff.RouteLocation> locations) {
        String prefix = "R" + target + "_";
        Set<String> authors = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String routeId : flow) {
            if (routeId != null && routeId.startsWith(prefix)) {
                authors.addAll(blameAuthors(locations.get(routeId)));
            }
        }
        return new ArrayList<>(authors);
    }

    /**
     * Map each business route to the backend APIs reachable from it, from this API's
     * graph. A backend is attributed to the <em>nearest</em> business route on the
     * path back to the API — walking transparently through shared/host call routes
     * (e.g. {@code callUFWDGE}, CamelHttpUri barrels) that sit between a business
     * route and the backend. Lets the UI auto-select a route's backend(s) when the
     * route is chosen.
     */
    private void collectRouteBackends(RouteGraph graph, Set<String> businessRoutes,
                                      Map<String, Set<String>> acc) {
        Map<String, GraphNode> byId = new HashMap<>();
        for (GraphNode n : graph.getNodes()) {
            byId.put(n.id(), n);
        }
        Map<String, List<String>> adj = new HashMap<>();
        for (GraphEdge e : graph.getEdges()) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            adj.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());   // undirected
        }
        for (GraphNode backend : byId.values()) {
            if (!GraphNode.TYPE_BACKEND.equals(backend.type())) {
                continue;
            }
            // BFS outward from the backend; the first business route(s) reached own it.
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            visited.add(backend.id());
            for (String nb : adj.getOrDefault(backend.id(), List.of())) {
                if (visited.add(nb)) {
                    queue.add(nb);
                }
            }
            while (!queue.isEmpty()) {
                GraphNode node = byId.get(queue.poll());
                if (node == null || !GraphNode.TYPE_ROUTE.equals(node.type())) {
                    continue;   // never expand through API or BACKEND nodes
                }
                if (businessRoutes.contains(node.label())) {
                    acc.computeIfAbsent(node.label(), k -> new TreeSet<>()).add(backend.label());
                    continue;   // stop at a business route → gives the nearest one
                }
                for (String nb : adj.getOrDefault(node.id(), List.of())) {   // shared/host: pass through
                    if (visited.add(nb)) {
                        queue.add(nb);
                    }
                }
            }
        }
    }

    private List<String> extractHosts(RouteGraph graph) {
        List<String> hosts = new ArrayList<>();
        for (GraphNode n : graph.getNodes()) {
            if (GraphNode.TYPE_ROUTE.equals(n.type())
                    && n.data() != null && Boolean.TRUE.equals(n.data().get("host"))) {
                hosts.add(n.label());
            }
        }
        return hosts;
    }

    /**
     * Labels of routes that are shared infrastructure rather than version-specific
     * business logic: CamelHttpUri hosts ({@code host=true}) and per-call terminal
     * routes drawn as {@code route:<id>#N} instances (e.g. callUFWDGE). These are
     * reused across all APIs/versions, so they are excluded from the impact route
     * change-picker to avoid false "everything is impacted" results.
     */
    private Set<String> sharedCallRoutes(RouteGraph graph) {
        Set<String> shared = new TreeSet<>();
        for (GraphNode n : graph.getNodes()) {
            if (!GraphNode.TYPE_ROUTE.equals(n.type())) continue;
            boolean host = n.data() != null && Boolean.TRUE.equals(n.data().get("host"));
            boolean perCallInstance = n.id() != null && n.id().contains("#");
            if (host || perCallInstance) shared.add(n.label());
        }
        return shared;
    }

    // --- shared preparation ---

    private Prepared prepare(TraceRequest request) {
        Roots roots = resolveRoots(request);
        SourceIndex index = scanCached(roots);
        List<String> warnings = new ArrayList<>(index.warnings());
        warnings.addAll(roots.depErrors());   // a dependency that failed to load is why an import may still be unresolved
        String country = (request.country() != null && !request.country().isBlank())
                ? request.country().trim() : null;
        RouteRegistry registry = country != null
                ? index.scopedRegistry(country, warnings)
                : index.fullRegistry();
        return new Prepared(index, registry, warnings, country);
    }

    /**
     * The operations to list / analyse for a request. Without a country, every discovered
     * operation. With a country, ONLY those wired into that country's bootstrap closure —
     * i.e. that have a route in the scoped registry — so a country view shows that country's
     * APIs and is not padded with endpoints whose routes live in another country (which would
     * otherwise show up as a large, misleading "(no route found)" bucket).
     */
    private List<OperationInfo> operationsInScope(Prepared prepared) {
        List<OperationInfo> all = prepared.index().operations().all();
        if (prepared.country() == null) {
            return all;
        }
        // One pass over the scoped routes → the set of operations they cover; then an O(1)
        // membership test per operation (instead of re-scanning every route per operation).
        Set<String> covered = prepared.registry().operationNames();
        List<OperationInfo> out = new ArrayList<>();
        for (OperationInfo op : all) {
            if (covered.contains(op.operationName())) {
                out.add(op);
            }
        }
        return out;
    }

    /** The primary source plus the resolved dependency roots for this request. */
    private Roots resolveRoots(TraceRequest request) {
        Path primary = resolveRoot(request);
        List<Path> deps = new ArrayList<>();
        List<String> depErrors = new ArrayList<>();
        for (String enc : request.dependencies()) {
            Path d = resolveDependency(enc, depErrors);
            if (d != null && !deps.contains(d)) {
                deps.add(d);
            }
        }
        return new Roots(primary, deps, depErrors);
    }

    /**
     * Resolve one encoded dependency source to a local directory: {@code local:<path>} →
     * the path as-is; {@code bit:<repoUrl>|<branch>} → a JGit checkout. A dependency that
     * can't be resolved (bad path, clone/auth failure) returns null AND records a reason in
     * {@code errors}, so the failure is surfaced to the user (as "needs review") rather than
     * silently hidden — otherwise an unresolved import looks the same whether the dependency
     * was never loaded or genuinely doesn't contain the file.
     */
    private Path resolveDependency(String enc, List<String> errors) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        String s = enc.trim();
        try {
            if (s.regionMatches(true, 0, "bit:", 0, 4)) {
                String rest = s.substring(4);
                int bar = rest.lastIndexOf('|');   // repo URLs carry no '|'; the branch follows it
                if (bar < 0) {
                    errors.add("Dependency source is malformed (expected repo|branch): " + rest);
                    return null;
                }
                String repo = rest.substring(0, bar).trim();
                String branch = rest.substring(bar + 1).trim();
                if (repo.isEmpty() || branch.isEmpty()) {
                    errors.add("Dependency source needs both a repo and a branch: " + rest);
                    return null;
                }
                return sourceResolver.resolve(repo, branch);
            }
            String path = s.regionMatches(true, 0, "local:", 0, 6) ? s.substring(6) : s;
            path = path.trim();
            if (path.isEmpty()) {
                return null;
            }
            Path p = Path.of(path);
            if (Files.isDirectory(p)) {
                return p;
            }
            errors.add("Dependency path not found: " + path);
            return null;
        } catch (RuntimeException e) {
            // A clone/fetch/auth failure — surface WHY so the user can fix it (e.g. a missing
            // bitbucket.token, a wrong branch, or an unreachable repo) instead of it being silent.
            errors.add("Dependency source could not be loaded (" + depLabel(s) + "): " + rootMessage(e));
            return null;
        }
    }

    /** A readable repo@branch (or path) label for a dependency error message. */
    private static String depLabel(String enc) {
        if (enc.regionMatches(true, 0, "bit:", 0, 4)) {
            String rest = enc.substring(4);
            int bar = rest.lastIndexOf('|');
            return bar >= 0 ? rest.substring(0, bar) + " @ " + rest.substring(bar + 1) : rest;
        }
        return enc.regionMatches(true, 0, "local:", 0, 6) ? enc.substring(6) : enc;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    // --- "needs review": warnings that leave the analysis incomplete ---

    private static final List<String> REVIEW_PREFIXES = List.of(
            "Unresolved <import>", "Unresolved <routeContextRef>",
            "Route not found in source", "Unresolved dynamic target",
            "Dynamic recipientList not resolved",
            "Dependency source could not be loaded", "Dependency path not found",
            "Dependency source is malformed", "Dependency source needs both");

    private static boolean isReview(String warning) {
        if (warning == null) {
            return false;
        }
        for (String p : REVIEW_PREFIXES) {
            if (warning.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Populate {@code needsReview} from the report's own warnings plus any harvested from the
     * per-API traces (whose {@link TraceResponse}s are otherwise discarded). Every review item
     * is also ensured present in {@code warnings} so the general warning banner never loses it —
     * the user must stay aware that some XMLs are still pending review.
     */
    private static void applyReview(List<String> warnings, List<String> needsReview,
                                    List<String> harvestedTraceWarnings) {
        LinkedHashSet<String> review = new LinkedHashSet<>();
        for (String w : warnings) {
            if (isReview(w)) {
                review.add(w);
            }
        }
        for (String w : harvestedTraceWarnings) {
            if (isReview(w)) {
                review.add(w);
            }
        }
        for (String r : review) {
            if (!warnings.contains(r)) {
                warnings.add(r);   // keep the warning visible; never silently drop it
            }
        }
        needsReview.addAll(review);
    }

    private Path resolveRoot(TraceRequest request) {
        // Bitbucket-branch mode: clone/fetch the repo at the branch and analyse that checkout.
        if (request.repo() != null && !request.repo().isBlank()) {
            return sourceResolver.resolve(request.repo(), request.branch());
        }
        // Local-path mode (unchanged).
        String sourceDir = (request.sourceDir() != null && !request.sourceDir().isBlank())
                ? request.sourceDir().trim()
                : defaultSourceDir;
        if (sourceDir == null || sourceDir.isBlank()) {
            throw new IllegalArgumentException(
                    "No source directory provided. Pass 'sourceDir' or set tracer.source-dir.");
        }
        Path root = Path.of(sourceDir);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Source directory does not exist: " + sourceDir);
        }
        return root;
    }

    // --- mode: single API ---

    private TraceResponse single(TraceRequest request, Prepared prepared) {
        TraceResponse response = new TraceResponse();
        response.setApi(request.api());
        response.setRequestedVersion(request.version());
        response.setTransferType(request.transferType());
        response.setCountry(prepared.country());
        response.setAvailableCountries(prepared.index().countries());
        response.getWarnings().addAll(prepared.warnings());

        OperationResolver operations = prepared.index().operations();
        String operationName = resolveOperation(request.api(), operations, response);
        if (operationName == null) {
            response.setGraph(new RouteGraph());
            applyReview(response.getWarnings(), response.getNeedsReview(), List.of());
            return response;
        }
        response.setOperationName(operationName);
        OperationInfo op = operations.resolve(request.api());
        if (op != null) {
            response.setCommand(op.command());
        }

        ResolvedRoute resolved =
                versionResolver.resolve(prepared.registry(), operationName, request.version());
        RouteGraph graph = new RouteGraph();
        traverseInto(response, request.api(), operationName, resolved,
                request.transferType(), prepared.registry(), graph, templateVersionResolver(request),
                request.version());
        response.setGraph(graph);
        // Traversal may have added "Route not found in source" for a direct: target that
        // lives in a dependency not yet added — surface those for review too.
        applyReview(response.getWarnings(), response.getNeedsReview(), List.of());
        return response;
    }

    // --- mode: catalog (no api) ---

    private CatalogResponse catalog(TraceRequest request, Prepared prepared) {
        CatalogResponse cat = new CatalogResponse();
        cat.setRequestedVersion(request.version());
        cat.setTransferType(request.transferType());
        cat.setCountry(prepared.country());
        cat.getAvailableCountries().addAll(prepared.index().countries());
        cat.getWarnings().addAll(prepared.warnings());

        RouteRegistry registry = prepared.registry();
        RouteGraph graph = new RouteGraph();
        boolean versionGiven = request.version() != null && !request.version().isBlank();

        List<OperationInfo> operations = operationsInScope(prepared);
        cat.setOperationCount(operations.size());
        if (operations.isEmpty()) {
            cat.getWarnings().add("No controller endpoints discovered in the source directory.");
        }
        int notInCountry = prepared.index().operations().all().size() - operations.size();
        if (notInCountry > 0) {
            cat.getWarnings().add(notInCountry + " API(s) are not wired into " + prepared.country()
                    + "'s bootstrap and were omitted from this country view.");
        }

        // With a specific client version, the catalog shows only the APIs actually
        // IMPACTED by that release — i.e. whose entry route is exactly that version.
        // APIs that fall back to a lower version / BASE are not part of the release
        // and are excluded (blank version = the whole code, every version).
        String wantedVersion = versionGiven ? request.version().trim() : null;
        int excluded = 0;

        var templateVersion = templateVersionResolver(request);
        Map<String, List<TraceResponse>> groups = new LinkedHashMap<>();
        for (OperationInfo op : operations) {
            for (ResolvedRoute target : targetsFor(op, registry, request, versionGiven)) {
                if (target == null) {
                    TraceResponse entry = newEntry(op, request.transferType());
                    entry.getWarnings().add("No route found for operation '" + op.operationName() + "'.");
                    groups.computeIfAbsent(NO_ROUTE_GROUP, k -> new ArrayList<>()).add(entry);
                    continue;
                }
                if (wantedVersion != null && !wantedVersion.equals(target.version())) {
                    excluded++;     // not impacted by the requested release
                    continue;
                }
                TraceResponse entry = newEntry(op, request.transferType());
                traverseInto(entry, op.path(), op.operationName(), target,
                        request.transferType(), registry, graph, templateVersion,
                        versionGiven ? request.version() : target.version());
                String key = target.version() != null ? target.version() : BASE_GROUP;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }
        if (excluded > 0) {
            cat.getWarnings().add(excluded + " API(s) are not impacted by version " + wantedVersion
                    + " (they resolve to a lower version or BASE) and were excluded.");
        }

        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort(this::compareGroupKeys);
        List<String> harvested = new ArrayList<>();
        for (String key : keys) {
            cat.getVersionsFound().add(key);
            cat.getGroups().add(new VersionGroup(key, groups.get(key)));
            for (TraceResponse entry : groups.get(key)) {
                harvested.addAll(entry.getWarnings());   // per-API "Route not found in source", etc.
            }
        }
        applyReview(cat.getWarnings(), cat.getNeedsReview(), harvested);
        cat.setGraph(graph);
        return cat;
    }

    /** The route targets to trace for an operation in catalog mode. */
    private List<ResolvedRoute> targetsFor(OperationInfo op, RouteRegistry registry,
                                           TraceRequest request, boolean versionGiven) {
        String name = op.operationName();
        List<ResolvedRoute> targets = new ArrayList<>();
        if (versionGiven) {
            targets.add(versionResolver.resolve(registry, name, request.version()));
            return targets;
        }
        List<String> versions = registry.availableVersionsFor(name);
        versions.sort((a, b) -> compareVersions(b, a));     // descending
        for (String v : versions) {
            targets.add(new ResolvedRoute("R" + v + "_" + name, v, false));
        }
        if (registry.contains(name)) {
            targets.add(new ResolvedRoute(name, null, true));
        }
        if (targets.isEmpty()) {
            targets.add(null);                              // signals "no route found"
        }
        return targets;
    }

    private TraceResponse newEntry(OperationInfo op, String transferType) {
        TraceResponse entry = new TraceResponse();
        entry.setApi(op.path());
        entry.setOperationName(op.operationName());
        entry.setCommand(op.command());
        entry.setTransferType(transferType);
        return entry;
    }

    // --- shared traversal ---

    private void traverseInto(TraceResponse response, String api, String operationName,
                              ResolvedRoute resolved, String transferType,
                              RouteRegistry registry, RouteGraph graph,
                              java.util.function.Function<String, String> templateVersion,
                              String clientVersion) {
        response.setResolvedRoute(resolved.routeName());
        response.setResolvedVersion(resolved.version());
        response.setBaseFallback(resolved.baseFallback());

        String apiNodeId = "api:" + (api != null ? api : operationName);
        String apiLabel = (api != null ? api : operationName) + "  [" + operationName + "]";
        graph.addNode(new GraphNode(apiNodeId, apiLabel, GraphNode.TYPE_API));
        new RouteTraverser(registry, graph, response, transferType, resolved.routeName(),
                templateVersion, destRouteResolver(registry), clientVersion)
                .trace(resolved.routeName(), apiNodeId);
    }

    /**
     * Resolve a DEST_ROUTE-style base name (e.g. {@code acceptcoreinfo}) at a given version to the
     * actual route it runs — the same version rule used for entry routes (highest {@code R<ver>_}
     * &le; the version, else BASE). Returns null when it doesn't resolve to a route that exists in
     * this scope, so a non-route constant is never mistaken for a dynamic {@code toD} target. The
     * traverser chooses the version (requested client version, else the calling route's own version).
     */
    private java.util.function.BiFunction<String, String, String> destRouteResolver(RouteRegistry registry) {
        return (base, version) -> {
            if (base == null || base.isBlank()) {
                return null;
            }
            ResolvedRoute rr = versionResolver.resolve(registry, base.trim(), version);
            String routeName = rr.routeName();
            return (routeName != null && registry.contains(routeName)) ? routeName : null;
        };
    }

    /**
     * A cached resolver: given a framework template {@code <to>} uri
     * (e.g. {@code framework:META-INF/templates/x/precapture.ftl}), find the file
     * under the source root and read its {@code "serviceVersionNumber"} — the
     * backend service version to send to the host.
     */
    private java.util.function.Function<String, String> templateVersionResolver(TraceRequest request) {
        List<Path> files;
        try {
            files = scanCached(resolveRoots(request)).allFiles();   // reuse the cached scan — no per-template walk
        } catch (RuntimeException e) {
            return uri -> null;   // no source dir → nothing to resolve
        }
        Map<String, String> cache = new java.util.HashMap<>();
        return uri -> cache.computeIfAbsent(uri, u -> resolveTemplateVersion(files, u));
    }

    // Tolerant of single/double quotes around the key and value: "serviceVersionNumber":"2.0" or '...':'2.0'.
    private static final java.util.regex.Pattern SERVICE_VERSION =
            java.util.regex.Pattern.compile("[\"']?serviceVersionNumber[\"']?\\s*:\\s*[\"']?([0-9][0-9.]*)[\"']?");

    private String resolveTemplateVersion(List<Path> files, String uri) {
        // Strip the component scheme (velocity:/freemarker:/framework:…) and any nested
        // classpath:/file: resource scheme, leaving the path to suffix-match in memory.
        String content = readTemplateContent(files, uri);
        if (content == null) {
            return null;
        }
        java.util.regex.Matcher m = SERVICE_VERSION.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /** Find the template file for a {@code <to>} uri (scheme-stripped, suffix-matched) and read it; null if absent. */
    private String readTemplateContent(List<Path> files, String uri) {
        String suffix = uri.contains(":") ? uri.substring(uri.indexOf(':') + 1) : uri;
        suffix = suffix.replace('\\', '/').trim();
        int q = suffix.indexOf('?');
        if (q >= 0) {
            suffix = suffix.substring(0, q);
        }
        suffix = suffix.replaceFirst("(?i)^(classpath\\*?|file):", "");
        while (suffix.startsWith("/") || suffix.startsWith("./") || suffix.startsWith("**/")) {
            suffix = suffix.startsWith("**/") ? suffix.substring(3) : suffix.replaceFirst("^\\.?/", "");
        }
        final String want = suffix;
        if (want.isEmpty()) {
            return null;
        }
        for (Path p : files) {
            if (p.toString().replace('\\', '/').endsWith(want)) {
                try {
                    return Files.readString(p);
                } catch (java.io.IOException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Like {@link #templateVersionResolver} but yields the template's JSON keys, for the payload diff. */
    private java.util.function.Function<String, List<PayloadKeys.KeyRef>> templateKeysResolver(TraceRequest request) {
        List<Path> files;
        try {
            files = scanCached(resolveRoots(request)).allFiles();
        } catch (RuntimeException e) {
            return uri -> List.of();
        }
        Map<String, List<PayloadKeys.KeyRef>> cache = new java.util.HashMap<>();
        return uri -> cache.computeIfAbsent(uri, u -> {
            String content = readTemplateContent(files, u);
            return content == null ? List.of() : PayloadKeys.extract(content);
        });
    }

    private String resolveOperation(String api, OperationResolver resolver, TraceResponse response) {
        OperationInfo op = resolver.resolve(api);
        if (op != null) {
            return op.operationName();
        }
        if (api != null && !api.isBlank() && !api.contains("/")) {
            response.getWarnings().add(
                    "No controller mapping matched; using '" + api + "' as the operation name.");
            return api.trim();
        }
        response.getWarnings().add(
                "No controller endpoint matched API path '" + api + "'. "
                        + "Discovered " + resolver.all().size() + " mapped endpoint(s).");
        return null;
    }

    // --- version ordering helpers ---

    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int compareGroupKeys(String a, String b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return ra == 0 ? compareVersions(b, a) : 0;          // versions descending
    }

    private int rank(String key) {
        if (key.equals(NO_ROUTE_GROUP)) {
            return 2;
        }
        return key.equals(BASE_GROUP) ? 1 : 0;
    }
}
