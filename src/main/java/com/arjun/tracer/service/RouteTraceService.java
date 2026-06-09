package com.arjun.tracer.service;

import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.RouteGraph;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.loader.CamelRouteModelLoader;
import com.arjun.tracer.loader.RouteModelLoader;
import com.arjun.tracer.loader.RouteRegistry;
import com.arjun.tracer.loader.XmlDomRouteModelLoader;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.resolve.OperationInfo;
import com.arjun.tracer.resolve.OperationResolver;
import com.arjun.tracer.resolve.VersionResolver;
import com.arjun.tracer.trace.RouteTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Orchestrates a trace: scan the source directory, resolve the operation and
 * version, then traverse the routes to build the graph.
 */
@Service
public class RouteTraceService {

    private static final Logger log = LoggerFactory.getLogger(RouteTraceService.class);

    /** Directory names skipped while scanning the source tree. */
    private static final Set<String> SKIP_DIRS =
            Set.of("target", "build", ".git", ".idea", "node_modules", ".mvn");

    private final String defaultSourceDir;
    private final CamelRouteModelLoader camelLoader = new CamelRouteModelLoader();
    private final XmlDomRouteModelLoader domLoader = new XmlDomRouteModelLoader();

    public RouteTraceService(@Value("${tracer.source-dir:}") String defaultSourceDir) {
        this.defaultSourceDir = defaultSourceDir;
    }

    public TraceResponse trace(TraceRequest request) {
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

        TraceResponse response = new TraceResponse();
        response.setApi(request.api());
        response.setRequestedVersion(request.version());
        response.setTransferType(request.transferType());

        OperationResolver operationResolver = new OperationResolver();
        RouteRegistry registry = new RouteRegistry();
        scan(root, operationResolver, registry, response);

        // 1. Resolve operation name from the API path (via controllers).
        String operationName = resolveOperation(request.api(), operationResolver, response);
        if (operationName == null) {
            response.setGraph(new RouteGraph());
            return response;
        }
        response.setOperationName(operationName);

        // 2. Resolve version with fallback, producing the entry route name.
        VersionResolver.ResolvedRoute resolved =
                new VersionResolver().resolve(registry, operationName, request.version());
        response.setResolvedRoute(resolved.routeName());
        response.setResolvedVersion(resolved.version());
        response.setBaseFallback(resolved.baseFallback());

        // 3. Build the graph by traversing from the entry route.
        RouteGraph graph = new RouteGraph();
        String apiLabel = (request.api() != null ? request.api() : operationName)
                + "  [" + operationName + "]";
        String apiNodeId = "api:" + (request.api() != null ? request.api() : operationName);
        graph.addNode(new GraphNode(apiNodeId, apiLabel, GraphNode.TYPE_API));

        RouteTraverser traverser = new RouteTraverser(
                registry, graph, response, request.transferType(), resolved.routeName());
        traverser.trace(resolved.routeName(), apiNodeId);

        response.setGraph(graph);
        return response;
    }

    private String resolveOperation(String api, OperationResolver resolver, TraceResponse response) {
        OperationInfo op = resolver.resolve(api);
        if (op != null) {
            response.setCommand(op.command());
            return op.operationName();
        }
        // No controller match. Allow passing an operation name directly (no slash).
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

    /** Walk the source tree once, feeding .java to the resolver and .xml to the registry. */
    private void scan(Path root, OperationResolver operationResolver,
                      RouteRegistry registry, TraceResponse response) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !isUnderSkippedDir(root, p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (name.endsWith(".java")) {
                            readQuietly(p).ifPresent(operationResolver::addSource);
                        } else if (name.endsWith(".xml")) {
                            readQuietly(p).ifPresent(xml -> loadRoutes(name, xml, registry, response));
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan source directory: " + root, e);
        }
    }

    /** Hybrid load: prefer the Camel RouteDefinition loader, fall back to DOM. */
    private void loadRoutes(String fileName, String xml, RouteRegistry registry, TraceResponse response) {
        List<RouteModel> routes = tryLoad(camelLoader, fileName, xml);
        if (routes == null || routes.isEmpty()) {
            List<RouteModel> domRoutes = tryLoad(domLoader, fileName, xml);
            if (domRoutes != null && !domRoutes.isEmpty()) {
                if (routes == null) {
                    response.getWarnings().add(
                            "Camel loader could not parse " + fileName + "; used DOM fallback.");
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
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private java.util.Optional<String> readQuietly(Path p) {
        try {
            return java.util.Optional.of(Files.readString(p));
        } catch (Exception e) {
            // unreadable / non-UTF8 file — skip it
            return java.util.Optional.empty();
        }
    }
}
