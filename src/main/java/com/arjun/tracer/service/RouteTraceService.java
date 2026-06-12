package com.arjun.tracer.service;

import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.GraphEdge;
import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.ImpactIndex;
import com.arjun.tracer.api.RouteGraph;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    public RouteTraceService(@Value("${tracer.source-dir:}") String defaultSourceDir) {
        this.defaultSourceDir = defaultSourceDir;
    }

    /** Scan result plus the registry chosen for this request's country scope. */
    private record Prepared(SourceIndex index, RouteRegistry registry, List<String> warnings, String country) {
    }

    /** Entry point used by the controller: returns a {@link TraceResponse} or {@link CatalogResponse}. */
    public Object analyze(TraceRequest request) {
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
        return scanner.scan(resolveRoot(request)).countries();
    }

    /**
     * Discovery metadata for the UI: available countries, release versions and
     * branch ({@code transferType}) values. Versions/branches honour the country
     * scope when one is given.
     */
    public Map<String, Object> meta(TraceRequest request) {
        SourceIndex index = scanner.scan(resolveRoot(request));
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
     */
    public ImpactIndex impactIndex(TraceRequest request) {
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

        for (OperationInfo op : prepared.index().operations().all()) {
            ResolvedRoute resolved =
                    versionResolver.resolve(prepared.registry(), op.operationName(), request.version());
            if (wantedVersion != null && !wantedVersion.equals(resolved.version())) {
                excluded++;
                continue;   // resolves to a lower version or BASE — not impacted by this release
            }
            TraceResponse r = new TraceResponse();
            RouteGraph graph = new RouteGraph();
            traverseInto(r, op.path(), op.operationName(), resolved,
                    request.transferType(), prepared.registry(), graph, templateVersion);
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
                    Map.copyOf(r.getBackendVersions())));

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
        return out;
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
        SourceIndex index = scanner.scan(resolveRoot(request));
        List<String> warnings = new ArrayList<>(index.warnings());
        String country = (request.country() != null && !request.country().isBlank())
                ? request.country().trim() : null;
        RouteRegistry registry = country != null
                ? index.scopedRegistry(country, warnings)
                : index.fullRegistry();
        return new Prepared(index, registry, warnings, country);
    }

    private Path resolveRoot(TraceRequest request) {
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
                request.transferType(), prepared.registry(), graph, templateVersionResolver(request));
        response.setGraph(graph);
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

        List<OperationInfo> operations = prepared.index().operations().all();
        cat.setOperationCount(operations.size());
        if (operations.isEmpty()) {
            cat.getWarnings().add("No controller endpoints discovered in the source directory.");
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
                        request.transferType(), registry, graph, templateVersion);
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
        for (String key : keys) {
            cat.getVersionsFound().add(key);
            cat.getGroups().add(new VersionGroup(key, groups.get(key)));
        }
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
                              java.util.function.Function<String, String> templateVersion) {
        response.setResolvedRoute(resolved.routeName());
        response.setResolvedVersion(resolved.version());
        response.setBaseFallback(resolved.baseFallback());

        String apiNodeId = "api:" + (api != null ? api : operationName);
        String apiLabel = (api != null ? api : operationName) + "  [" + operationName + "]";
        graph.addNode(new GraphNode(apiNodeId, apiLabel, GraphNode.TYPE_API));
        new RouteTraverser(registry, graph, response, transferType, resolved.routeName(), templateVersion)
                .trace(resolved.routeName(), apiNodeId);
    }

    /**
     * A cached resolver: given a framework template {@code <to>} uri
     * (e.g. {@code framework:META-INF/templates/x/precapture.ftl}), find the file
     * under the source root and read its {@code "serviceVersionNumber"} — the
     * backend service version to send to the host.
     */
    private java.util.function.Function<String, String> templateVersionResolver(TraceRequest request) {
        Path root;
        try {
            root = resolveRoot(request);
        } catch (RuntimeException e) {
            return uri -> null;   // no source dir → nothing to resolve
        }
        Map<String, String> cache = new java.util.HashMap<>();
        return uri -> cache.computeIfAbsent(uri, u -> resolveTemplateVersion(root, u));
    }

    // Tolerant of single/double quotes around the key and value: "serviceVersionNumber":"2.0" or '...':'2.0'.
    private static final java.util.regex.Pattern SERVICE_VERSION =
            java.util.regex.Pattern.compile("[\"']?serviceVersionNumber[\"']?\\s*:\\s*[\"']?([0-9][0-9.]*)[\"']?");

    private String resolveTemplateVersion(Path root, String uri) {
        // Strip the component scheme (velocity:/freemarker:/framework:…) and any nested
        // classpath:/file: resource scheme, leaving the path to suffix-match under the root.
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
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            java.util.Optional<Path> file = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').endsWith(want))
                    .findFirst();
            if (file.isEmpty()) {
                return null;
            }
            java.util.regex.Matcher m = SERVICE_VERSION.matcher(Files.readString(file.get()));
            return m.find() ? m.group(1) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
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
