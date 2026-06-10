package com.arjun.tracer.service;

import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.RouteGraph;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.api.VersionGroup;
import com.arjun.tracer.loader.CamelRouteModelLoader;
import com.arjun.tracer.loader.RouteModelLoader;
import com.arjun.tracer.loader.RouteRegistry;
import com.arjun.tracer.loader.XmlDomRouteModelLoader;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.resolve.OperationInfo;
import com.arjun.tracer.resolve.OperationResolver;
import com.arjun.tracer.resolve.VersionResolver;
import com.arjun.tracer.resolve.VersionResolver.ResolvedRoute;
import com.arjun.tracer.trace.RouteTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Orchestrates analysis. Three input modes:
 *
 * <ul>
 *   <li><b>api + version</b> → single trace of that exact resolution;</li>
 *   <li><b>api only</b> → single trace (blank version resolves to BASE);</li>
 *   <li><b>no api</b> → catalog: every discovered API, grouped by the client
 *       release version its route resolves to.</li>
 * </ul>
 */
@Service
public class RouteTraceService {

    private static final Logger log = LoggerFactory.getLogger(RouteTraceService.class);

    /** Directory names skipped while scanning the source tree. */
    private static final Set<String> SKIP_DIRS =
            Set.of("target", "build", ".git", ".idea", "node_modules", ".mvn");

    private static final String BASE_GROUP = "BASE";
    private static final String NO_ROUTE_GROUP = "(no route found)";

    private final String defaultSourceDir;
    private final CamelRouteModelLoader camelLoader = new CamelRouteModelLoader();
    private final XmlDomRouteModelLoader domLoader = new XmlDomRouteModelLoader();
    private final VersionResolver versionResolver = new VersionResolver();

    public RouteTraceService(@Value("${tracer.source-dir:}") String defaultSourceDir) {
        this.defaultSourceDir = defaultSourceDir;
    }

    /** Holds the result of scanning the source tree once per request. */
    private record Scanned(OperationResolver operations, RouteRegistry registry, List<String> warnings) {
    }

    /** Entry point used by the controller: returns a {@link TraceResponse} or {@link CatalogResponse}. */
    public Object analyze(TraceRequest request) {
        Scanned scanned = prepare(request);
        boolean hasApi = request.api() != null && !request.api().isBlank();
        return hasApi ? single(request, scanned) : catalog(request, scanned);
    }

    /** Single-API trace (kept for direct use / tests). */
    public TraceResponse trace(TraceRequest request) {
        return single(request, prepare(request));
    }

    // --- shared preparation ---

    private Scanned prepare(TraceRequest request) {
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
        OperationResolver operations = new OperationResolver();
        RouteRegistry registry = new RouteRegistry();
        List<String> warnings = new ArrayList<>();
        scan(root, operations, registry, warnings);
        return new Scanned(operations, registry, warnings);
    }

    // --- mode: single API ---

    private TraceResponse single(TraceRequest request, Scanned scanned) {
        TraceResponse response = new TraceResponse();
        response.setApi(request.api());
        response.setRequestedVersion(request.version());
        response.setTransferType(request.transferType());
        response.getWarnings().addAll(scanned.warnings());

        String operationName = resolveOperation(request.api(), scanned.operations(), response);
        if (operationName == null) {
            response.setGraph(new RouteGraph());
            return response;
        }
        response.setOperationName(operationName);
        OperationInfo op = scanned.operations().resolve(request.api());
        if (op != null) {
            response.setCommand(op.command());
        }

        ResolvedRoute resolved =
                versionResolver.resolve(scanned.registry(), operationName, request.version());
        RouteGraph graph = new RouteGraph();
        traverseInto(response, request.api(), operationName, resolved,
                request.transferType(), scanned.registry(), graph);
        response.setGraph(graph);
        return response;
    }

    // --- mode: catalog (no api) ---

    private CatalogResponse catalog(TraceRequest request, Scanned scanned) {
        CatalogResponse cat = new CatalogResponse();
        cat.setRequestedVersion(request.version());
        cat.setTransferType(request.transferType());
        cat.getWarnings().addAll(scanned.warnings());

        RouteRegistry registry = scanned.registry();
        RouteGraph graph = new RouteGraph();
        boolean versionGiven = request.version() != null && !request.version().isBlank();

        List<OperationInfo> operations = scanned.operations().all();
        cat.setOperationCount(operations.size());
        if (operations.isEmpty()) {
            cat.getWarnings().add("No controller endpoints discovered in the source directory.");
        }

        Map<String, List<TraceResponse>> groups = new LinkedHashMap<>();
        for (OperationInfo op : operations) {
            for (ResolvedRoute target : targetsFor(op, registry, request, versionGiven)) {
                if (target == null) {                       // operation with no route at all
                    TraceResponse entry = newEntry(op, request.transferType());
                    entry.getWarnings().add("No route found for operation '" + op.operationName() + "'.");
                    groups.computeIfAbsent(NO_ROUTE_GROUP, k -> new ArrayList<>()).add(entry);
                    continue;
                }
                TraceResponse entry = newEntry(op, request.transferType());
                traverseInto(entry, op.path(), op.operationName(), target,
                        request.transferType(), registry, graph);
                String key = target.version() != null ? target.version() : BASE_GROUP;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
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
            // What this API resolves to for the requested client version (with fallback).
            targets.add(versionResolver.resolve(registry, name, request.version()));
            return targets;
        }
        // No version: enumerate every available version, highest first, then BASE.
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

    // --- shared traversal: add the API node and walk from the entry route ---

    private void traverseInto(TraceResponse response, String api, String operationName,
                              ResolvedRoute resolved, String transferType,
                              RouteRegistry registry, RouteGraph graph) {
        response.setResolvedRoute(resolved.routeName());
        response.setResolvedVersion(resolved.version());
        response.setBaseFallback(resolved.baseFallback());

        String apiNodeId = "api:" + (api != null ? api : operationName);
        String apiLabel = (api != null ? api : operationName) + "  [" + operationName + "]";
        graph.addNode(new GraphNode(apiNodeId, apiLabel, GraphNode.TYPE_API));
        new RouteTraverser(registry, graph, response, transferType, resolved.routeName())
                .trace(resolved.routeName(), apiNodeId);
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

    /** Numeric component-wise comparison of dotted versions ("9.10" > "9.9"). */
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

    /** Order groups: real versions (highest first), then BASE, then the no-route bucket. */
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

    // --- source scanning ---

    /** Walk the source tree once, feeding .java to the resolver and .xml to the registry. */
    private void scan(Path root, OperationResolver operations, RouteRegistry registry, List<String> warnings) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !isUnderSkippedDir(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (name.endsWith(".java")) {
                            readQuietly(p).ifPresent(operations::addSource);
                        } else if (name.endsWith(".xml")) {
                            readQuietly(p).ifPresent(xml -> loadRoutes(name, xml, registry, warnings));
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan source directory: " + root, e);
        }
    }

    /** Hybrid load: prefer the Camel RouteDefinition loader, fall back to DOM. */
    private void loadRoutes(String fileName, String xml, RouteRegistry registry, List<String> warnings) {
        List<RouteModel> routes = tryLoad(camelLoader, fileName, xml);
        if (routes == null || routes.isEmpty()) {
            List<RouteModel> domRoutes = tryLoad(domLoader, fileName, xml);
            if (domRoutes != null && !domRoutes.isEmpty()) {
                if (routes == null) {
                    warnings.add("Camel loader could not parse " + fileName + "; used DOM fallback.");
                }
                routes = domRoutes;
            }
        }
        if (routes != null) {
            routes.forEach(registry::add);
        }
    }

    private List<RouteModel> tryLoad(RouteModelLoader loader, String fileName, String xml) {
        try {
            return loader.load(fileName, xml);
        } catch (Exception e) {
            log.debug("{} failed on {}: {}", loader.getClass().getSimpleName(), fileName, e.toString());
            return null;
        }
    }

    private boolean isUnderSkippedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        String prev = "";
        for (Path part : rel) {
            String seg = part.toString();
            if (SKIP_DIRS.contains(seg)) {
                return true;
            }
            // Skip test source roots so test controllers/routes are not catalogued:
            // .../src/test/... and the user's .../src/main/test/... layout.
            if (seg.equals("test") && (prev.equals("src") || prev.equals("main"))) {
                return true;
            }
            prev = seg;
        }
        return false;
    }

    private Optional<String> readQuietly(Path p) {
        try {
            return Optional.of(Files.readString(p));
        } catch (Exception e) {
            return Optional.empty();                          // unreadable / non-UTF8 — skip
        }
    }
}
