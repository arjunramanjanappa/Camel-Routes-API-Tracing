package com.arjun.tracer.scan;

import com.arjun.tracer.loader.CamelRouteModelLoader;
import com.arjun.tracer.loader.RouteModelLoader;
import com.arjun.tracer.loader.XmlDomRouteModelLoader;
import com.arjun.tracer.model.RouteModel;
import com.arjun.tracer.resolve.OperationResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks a source directory once and produces a {@link SourceIndex}: parses
 * controllers (for operation names) and route XML (routes + assembly metadata),
 * using the hybrid Camel→DOM loader. Test source roots and build dirs are
 * skipped.
 */
public class SourceScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceScanner.class);

    private static final Set<String> SKIP_DIRS =
            Set.of("target", "build", ".git", ".idea", "node_modules", ".mvn");

    private final CamelRouteModelLoader camelLoader = new CamelRouteModelLoader();
    private final XmlDomRouteModelLoader domLoader = new XmlDomRouteModelLoader();

    public SourceIndex scan(Path root) {
        return scan(root, List.of());
    }

    /**
     * Scan the primary source root plus any dependency roots, merging them into one
     * {@link SourceIndex}. Dependency roots provide route XMLs that the primary source
     * {@code <import>}s but doesn't itself contain (routes packaged in a shared library);
     * merging them means those imports resolve and the routes flow into the registry, trace
     * and diff exactly as if they were in the primary tree. Each file keeps a path relative to
     * its own root, so a {@code classpath:foo/bar.xml} import still suffix-matches a dependency
     * file. Nothing is deduplicated across roots — a dependency is expected to be disjoint code.
     */
    public SourceIndex scan(Path primary, List<Path> deps) {
        OperationResolver operations = new OperationResolver();
        List<FileInfo> files = new ArrayList<>();
        List<Path> allFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RouteIncludePattern> includes = new ArrayList<>();

        scanInto(primary, operations, files, allFiles, warnings, includes, false);
        for (Path dep : deps) {
            if (dep != null) {
                scanInto(dep, operations, files, allFiles, warnings, includes, true);
            }
        }
        return new SourceIndex(operations, files, allFiles, warnings, includes);
    }

    // application.yml / application-<profile>.yml / .yaml / .properties — the config that can carry a
    // camel routes-include-pattern (the second bootstrap-discovery way).
    private static final java.util.regex.Pattern APP_CONFIG =
            java.util.regex.Pattern.compile("application(?:-([A-Za-z0-9_-]+))?\\.(?:yml|yaml|properties)");
    // routes-include-pattern: <value>   (yaml)   OR   ...routes-include-pattern=<value>   (properties)
    private static final java.util.regex.Pattern INCLUDE_PATTERN =
            java.util.regex.Pattern.compile("routes-include-pattern\\s*[:=]\\s*(.+)");

    /** Walk one root and add its controllers / route XMLs / config include-patterns to the index state. */
    private void scanInto(Path root, OperationResolver operations, List<FileInfo> files,
                          List<Path> allFiles, List<String> warnings, List<RouteIncludePattern> includes,
                          boolean fromDependency) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !isUnderSkippedDir(root, p))
                    .forEach(p -> {
                        allFiles.add(p);
                        String name = p.getFileName().toString();
                        if (name.endsWith(".java")) {
                            // Only a controller declares an @*Mapping; parsing the rest with
                            // JavaParser (the dominant scan cost) yields nothing. Cheap substring
                            // pre-filter skips DTOs/services/utils entirely.
                            readQuietly(p).ifPresent(src -> {
                                if (src.contains("Mapping")) {
                                    operations.addSource(src);
                                }
                            });
                        } else if (name.endsWith(".xml")) {
                            readQuietly(p).ifPresent(xml ->
                                    files.add(toFileInfo(root, p, xml, warnings, fromDependency)));
                        } else {
                            java.util.regex.Matcher cfg = APP_CONFIG.matcher(name);
                            if (cfg.matches()) {
                                readQuietly(p).ifPresent(text ->
                                        collectIncludes(cfg.group(1), text, includes));
                            }
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan source directory: " + root, e);
        }
    }

    /** Pull every {@code routes-include-pattern} value out of one config file (comma-separated split). */
    private void collectIncludes(String profile, String text, List<RouteIncludePattern> includes) {
        for (String line : text.split("\\R")) {
            java.util.regex.Matcher m = INCLUDE_PATTERN.matcher(line);
            if (m.find()) {
                String value = m.group(1).trim();
                for (String one : value.split(",")) {
                    String v = one.trim();
                    if (!v.isEmpty()) {
                        includes.add(new RouteIncludePattern(profile, v));
                    }
                }
            }
        }
    }

    /**
     * A cheap, parse-free fingerprint of the files this scanner would index for
     * {@code root} — same directory pruning as {@link #scan}, but it only stats each
     * file (path + size + last-modified) instead of reading/parsing it. The value
     * changes whenever any indexed file is added, removed, or modified, so callers can
     * detect on-disk edits and reuse their caches until something actually changes.
     *
     * <p>The per-file contributions are summed, so the result is independent of the
     * order {@link Files#walk} yields. Returns {@code 0} if the tree can't be walked.
     */
    public long fingerprint(Path root) {
        long[] acc = {0L};
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !isUnderSkippedDir(root, p))
                    .forEach(p -> {
                        long h = p.toString().hashCode() & 0xffffffffL;
                        try {
                            java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(
                                    p, java.nio.file.attribute.BasicFileAttributes.class);
                            h = h * 1099511628211L + a.lastModifiedTime().toMillis();
                            h = h * 31 + a.size();
                        } catch (IOException ignore) {
                            // unreadable attributes — fold the path alone; still detects add/remove
                        }
                        acc[0] += h;
                    });
        } catch (IOException e) {
            return 0L;   // unwalkable — treat as unknown so the caller recomputes
        }
        return acc[0];
    }

    private FileInfo toFileInfo(Path root, Path file, String xml, List<String> warnings, boolean fromDependency) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        List<RouteModel> routes = loadRoutes(file.getFileName().toString(), xml, warnings);
        RouteXmlMetadata metadata = RouteXmlMetadata.parse(xml);
        // Flag routes that perform the backend HTTP call (they reference CamelHttpUri).
        Set<String> hostIds = metadata.hostRouteIds();
        if (!hostIds.isEmpty()) {
            routes = routes.stream()
                    .map(r -> hostIds.contains(r.routeId()) ? r.asHost() : r)
                    .toList();
        }
        return new FileInfo(relPath, routes, metadata, fromDependency);
    }

    /** Hybrid load: prefer the Camel RouteDefinition loader, fall back to DOM. */
    private List<RouteModel> loadRoutes(String fileName, String xml, List<String> warnings) {
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
        return routes != null ? routes : List.of();
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
            // Skip test source roots: .../src/test/... and the user's .../src/main/test/...
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
