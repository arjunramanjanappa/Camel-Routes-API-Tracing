package com.arjun.tracer.scan;

import com.arjun.tracer.loader.RouteRegistry;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.resolve.OperationResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The result of scanning a source tree once: the controllers, every route file
 * (with its assembly metadata), and the indexes needed to scope analysis to a
 * single country bootstrap.
 */
public class SourceIndex {

    private final OperationResolver operations;
    private final List<FileInfo> files;
    private final List<java.nio.file.Path> allFiles;
    private final List<String> warnings;

    /** routeContext id → the file that defines it. */
    private final Map<String, FileInfo> contextIdToFile = new LinkedHashMap<>();
    /** bootstrap (country) name → its file. */
    private final Map<String, FileInfo> countryToFile = new LinkedHashMap<>();
    /** Dependency-source files (shared/core host routes), collected once — always in every country scope. */
    private final List<FileInfo> dependencyFiles = new ArrayList<>();

    public SourceIndex(OperationResolver operations, List<FileInfo> files,
                       List<java.nio.file.Path> allFiles, List<String> warnings) {
        this.operations = operations;
        this.files = files;
        this.allFiles = allFiles;
        this.warnings = warnings;
        index();
    }

    /** Every regular source file found in the scan — for in-memory template lookups. */
    public List<java.nio.file.Path> allFiles() {
        return allFiles;
    }

    private void index() {
        for (FileInfo f : files) {
            for (String ctx : f.metadata().definedContexts()) {
                contextIdToFile.putIfAbsent(ctx, f);
            }
            if (f.metadata().hasCamelContext()) {
                countryToFile.putIfAbsent(f.baseName(), f);
            }
            if (f.fromDependency()) {
                dependencyFiles.add(f);
            }
        }
    }

    public OperationResolver operations() {
        return operations;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Bootstrap names available as scopes, sorted (e.g. ID, MY, SG, TH, VN). */
    public List<String> countries() {
        return new ArrayList<>(new TreeSet<>(countryToFile.keySet()));
    }

    /** A registry containing every route in the tree (no country filter). */
    public RouteRegistry fullRegistry() {
        RouteRegistry registry = new RouteRegistry();
        for (FileInfo f : files) {
            f.routes().forEach(registry::add);
        }
        return registry;
    }

    /**
     * A registry scoped to one country: the routes of the bootstrap file plus
     * everything it pulls in transitively via {@code <import>} and
     * {@code <routeContextRef>}. Unresolved references are reported into
     * {@code scopeWarnings}.
     */
    public RouteRegistry scopedRegistry(String country, List<String> scopeWarnings) {
        RouteRegistry registry = new RouteRegistry();
        FileInfo start = countryToFile.get(country);
        if (start == null) {
            scopeWarnings.add("Unknown country '" + country + "'. Available: " + countries());
            return registry;
        }
        Set<FileInfo> closure = closureOf(start, scopeWarnings);
        // Dependency files are country- and version-agnostic shared/core routes (host resolution),
        // not country bootstraps — so they are ALWAYS in scope, even when the country bootstrap
        // reaches them via direct: rather than an <import>. Union them into the closure so a host
        // defined in a dependency resolves regardless of the selected country.
        Set<FileInfo> included = new java.util.LinkedHashSet<>(closure);
        included.addAll(dependencyFiles);   // precomputed once — no per-call rescan of the merged file list
        int routeCount = 0;
        for (FileInfo f : included) {
            for (RouteModel r : f.routes()) {
                registry.add(r);
                routeCount++;
            }
        }
        if (routeCount == 0) {
            scopeWarnings.add("Country '" + country + "' resolved to no routes "
                    + "(check its <import>/<routeContextRef> targets are inside the source directory).");
        }
        return registry;
    }

    /** Transitive set of files reachable from a bootstrap via imports + context refs. */
    private Set<FileInfo> closureOf(FileInfo start, List<String> scopeWarnings) {
        Set<FileInfo> visited = new HashSet<>();
        Deque<FileInfo> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            FileInfo f = queue.poll();
            if (!visited.add(f)) {
                continue;
            }
            for (String imp : f.metadata().imports()) {
                List<FileInfo> targets = resolveImport(imp);
                if (targets.isEmpty()) {
                    scopeWarnings.add("Unresolved <import> in " + f.relPath() + ": " + imp);
                }
                queue.addAll(targets);
            }
            for (String ref : f.metadata().contextRefs()) {
                FileInfo target = contextIdToFile.get(ref);
                if (target == null) {
                    scopeWarnings.add("Unresolved <routeContextRef ref=\"" + ref + "\"> in " + f.relPath());
                } else {
                    queue.add(target);
                }
            }
        }
        return visited;
    }

    /**
     * Resolve an {@code <import resource>} to scanned files. Handles
     * {@code classpath:}/{@code classpath*:}/{@code file:} schemes and a trailing
     * {@code *} wildcard, matching against file paths by suffix.
     */
    private List<FileInfo> resolveImport(String resource) {
        String r = resource.trim();
        int scheme = r.indexOf(':');
        if (scheme >= 0 && r.regionMatches(true, 0, "classpath", 0, "classpath".length())) {
            r = r.substring(scheme + 1);
        } else if (r.regionMatches(true, 0, "file:", 0, "file:".length())) {
            r = r.substring("file:".length());
        }
        r = r.replace('\\', '/');
        while (r.startsWith("/") || r.startsWith("./") || r.startsWith("**/")) {
            r = r.startsWith("**/") ? r.substring(3) : r.replaceFirst("^\\.?/", "");
        }

        List<FileInfo> matches = new ArrayList<>();
        int star = r.indexOf('*');
        if (star >= 0) {
            String prefix = r.substring(0, star);                 // directory part before the wildcard
            for (FileInfo f : files) {
                if (("/" + f.relPath()).contains("/" + prefix) || f.relPath().contains(prefix)) {
                    matches.add(f);
                }
            }
        } else {
            for (FileInfo f : files) {
                if (f.relPath().equals(r) || f.relPath().endsWith("/" + r)) {
                    matches.add(f);
                }
            }
        }
        return matches;
    }
}
