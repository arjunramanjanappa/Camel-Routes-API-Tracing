package com.uob.tracer.scan;

import com.uob.tracer.loader.RouteRegistry;
import com.uob.tracer.model.RouteModel;
import com.uob.tracer.resolve.OperationResolver;

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

    /**
     * routeContext id → every file that defines it. Usually one, but multiple countries commonly declare the
     * SAME id (e.g. {@code securityContext9.14}) in their own per-country files; a {@code routeContextRef}
     * must then resolve to the file belonging to the country being scoped, not whichever was scanned first.
     */
    private final Map<String, List<FileInfo>> contextIdToFiles = new LinkedHashMap<>();
    /**
     * bootstrap (country) name → its file(s). Case-insensitive (the UI-typed country ↔ the
     * filename/profile). A country usually has one bootstrap ({@code SG.xml}), but a
     * routes-include-pattern country can resolve to several files (e.g. {@code routes/sg/*.xml} plus a
     * shared {@code routes.xml}), so the value is a list.
     */
    private final Map<String, List<FileInfo>> countryToFiles = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Dependency-source files (shared/core host routes), collected once — always in every country scope. */
    private final List<FileInfo> dependencyFiles = new ArrayList<>();
    /**
     * Files from a default {@code application.yml} {@code routes-include-pattern} entry that carries no
     * country dimension — a shared {@code routes.xml}, a glob ({@code routes/*.xml}), or a literal list.
     * The application config is the source of truth for what loads, so these are ALWAYS in every
     * country's scope (the controller-country filter then scopes which APIs are shown).
     */
    private final Set<FileInfo> sharedIncludeFiles = new java.util.LinkedHashSet<>();
    /** camel routes-include-pattern entries from application*.yml — the second bootstrap-discovery way. */
    private final List<RouteIncludePattern> includePatterns;
    /** application.properties (+ flat config) key→value, for resolving {@code {{key}}} in route names. */
    private final Map<String, String> properties;
    /** Spring bean name → its source-relative {@code .java} path — resolves {@code bean:name} to a class. */
    private final Map<String, String> beans;
    /**
     * True when the source shows the intercepted-UFW dispatcher (a {@code direct:redirectRoute} /
     * dynamic {@code send${...}Route} toD). Auto-selects the SPL-Secure resolver for this repo:
     * an API's entry route is {@code send<command>Route} / {@code send<method>Route}.
     */
    private boolean commandDispatch;

    public SourceIndex(OperationResolver operations, List<FileInfo> files,
                       List<java.nio.file.Path> allFiles, List<String> warnings) {
        this(operations, files, allFiles, warnings, List.of());
    }

    public SourceIndex(OperationResolver operations, List<FileInfo> files,
                       List<java.nio.file.Path> allFiles, List<String> warnings,
                       List<RouteIncludePattern> includePatterns) {
        this(operations, files, allFiles, warnings, includePatterns, Map.of(), Map.of());
    }

    public SourceIndex(OperationResolver operations, List<FileInfo> files,
                       List<java.nio.file.Path> allFiles, List<String> warnings,
                       List<RouteIncludePattern> includePatterns, Map<String, String> properties) {
        this(operations, files, allFiles, warnings, includePatterns, properties, Map.of());
    }

    public SourceIndex(OperationResolver operations, List<FileInfo> files,
                       List<java.nio.file.Path> allFiles, List<String> warnings,
                       List<RouteIncludePattern> includePatterns, Map<String, String> properties,
                       Map<String, String> beans) {
        this.operations = operations;
        this.files = files;
        this.allFiles = allFiles;
        this.warnings = warnings;
        this.includePatterns = includePatterns;
        this.properties = properties == null ? Map.of() : properties;
        this.beans = beans == null ? Map.of() : beans;
        index();
    }

    /** application.properties (+ flat config) values — resolves {@code {{key}}} placeholders in route names. */
    public Map<String, String> properties() {
        return properties;
    }

    /** Spring bean name → source-relative {@code .java} path, for {@code bean:name} → class resolution. */
    public Map<String, String> beans() {
        return beans;
    }

    /** Every regular source file found in the scan — for in-memory template lookups. */
    public List<java.nio.file.Path> allFiles() {
        return allFiles;
    }

    private void index() {
        for (FileInfo f : files) {
            for (String ctx : f.metadata().definedContexts()) {
                contextIdToFiles.computeIfAbsent(ctx, k -> new ArrayList<>()).add(f);
            }
            // A filename bootstrap (SG.xml/MY.xml) counts only if it actually brings routes. An empty
            // <camelContext> shell — a repo whose real routes load via application.yml
            // routes-include-pattern, with a vestigial MY.xml carrying no routes — is ignored, so the
            // routes-include-pattern way is used instead (the same as deleting MY.xml).
            if (f.metadata().hasCamelContext() && bootstrapBringsRoutes(f)) {
                countryToFiles.computeIfAbsent(f.baseName(), k -> new ArrayList<>()).add(f);
            }
            if (f.fromDependency()) {
                dependencyFiles.add(f);
            }
            if (f.metadata().commandDispatch()) {
                commandDispatch = true;
            }
        }
        // Second way (ONLY when the filename way found no non-empty bootstrap): the countries come from
        // the camel routes-include-pattern in application*.yml.
        if (countryToFiles.isEmpty()) {
            detectCountriesFromIncludes();
        }
    }

    /**
     * A filename bootstrap actually brings routes if it defines its own {@code <route>}s, or pulls
     * some in via {@code <import>} / {@code <routeContextRef>}. An empty {@code <camelContext>} shell
     * (a repo that loads routes purely through {@code application.yml routes-include-pattern}) brings
     * none, so it must not shadow the routes-include-pattern discovery.
     */
    private static boolean bootstrapBringsRoutes(FileInfo f) {
        return !f.routes().isEmpty()
                || !f.metadata().imports().isEmpty()
                || !f.metadata().contextRefs().isEmpty();
    }

    /** Resolve routes-include-pattern entries to country → bootstrap-file(s), populating {@link #countryToFiles}. */
    private void detectCountriesFromIncludes() {
        boolean hasProfiles = includePatterns.stream().anyMatch(i -> i.profile() != null);
        for (RouteIncludePattern inc : includePatterns) {
            // application-<country>.yml take priority; the default application.yml is used only when
            // there are no profile-specific configs.
            if (hasProfiles && inc.profile() == null) {
                continue;
            }
            String pattern = stripScheme(inc.pattern());
            if (inc.profile() != null) {
                // application-<profile>.yml → country = profile; scope = every file its patterns resolve to
                // (a routes/<country>/*.xml wildcard, plus a shared routes.xml — both listed in the config).
                String concrete = pattern.contains("${") ? substitutePlaceholder(pattern, inc.profile()) : pattern;
                addFiles(inc.profile(), matchFiles(concrete));
            } else if (pattern.contains("${")) {
                // default application.yml with a ${country} placeholder → the segment matching the
                // placeholder is the country (secure-my.xml → "my", or routes/my/*.xml → "my").
                java.util.regex.Pattern rx = placeholderRegex(pattern);
                for (FileInfo f : files) {
                    java.util.regex.Matcher m = rx.matcher(f.relPath().replace('\\', '/'));
                    if (m.matches()) {
                        addFiles(m.group(1), List.of(f));
                    }
                }
            } else {
                // default application.yml entry with no ${country} and no profile — a shared routes.xml,
                // a glob (routes/*.xml), or a literal list. It has no country signal of its own; the config
                // is the source of truth, so it loads for EVERY country (controller-country then scopes
                // which APIs are shown). This is also what pulls in a shared routes.xml that is listed
                // alongside a ${country} placeholder in the same pattern.
                sharedIncludeFiles.addAll(matchFiles(pattern));
            }
        }
    }

    private void addFiles(String country, List<FileInfo> matched) {
        if (!matched.isEmpty()) {
            countryToFiles.computeIfAbsent(country, k -> new ArrayList<>()).addAll(matched);
        }
    }

    private static String stripScheme(String pattern) {
        String p = pattern.trim().replaceFirst("(?i)^(classpath\\*?|file):", "").replace('\\', '/');
        // A leading '/' (or './') is root-relative — e.g. classpath:/sg/*.xml or /${country}/*.xml.
        // Scanned file paths are root-relative WITHOUT a leading slash, so drop it or the match fails.
        while (p.startsWith("/") || p.startsWith("./")) {
            p = p.startsWith("./") ? p.substring(2) : p.substring(1);
        }
        return p;
    }

    private static String substitutePlaceholder(String pattern, String value) {
        return pattern.replaceAll("\\$\\{[^}]*\\}", java.util.regex.Matcher.quoteReplacement(value));
    }

    /** Every scanned file matching an Ant-style path pattern (handles {@code *}, {@code **}, {@code ?}). */
    private List<FileInfo> matchFiles(String pattern) {
        String want = pattern.replace('\\', '/');
        List<FileInfo> out = new ArrayList<>();
        if (want.indexOf('*') < 0 && want.indexOf('?') < 0) {
            for (FileInfo f : files) {
                if (f.relPath().equals(want) || f.relPath().endsWith("/" + want)) {
                    out.add(f);
                }
            }
            return out;
        }
        java.util.regex.Pattern rx = java.util.regex.Pattern.compile("(?:^|.*/)" + antBody(want) + "$");
        for (FileInfo f : files) {
            if (rx.matcher(f.relPath().replace('\\', '/')).matches()) {
                out.add(f);
            }
        }
        return out;
    }

    /** A regex matching a file path whose tail is the pattern, the {@code ${…}} placeholder captured as group 1. */
    private static java.util.regex.Pattern placeholderRegex(String pattern) {
        int start = pattern.indexOf("${");
        int end = pattern.indexOf('}', start);
        String prefix = pattern.substring(0, start);
        String suffix = end >= 0 ? pattern.substring(end + 1) : "";
        // prefix/suffix may themselves carry wildcards (e.g. routes/${country}/*.xml).
        return java.util.regex.Pattern.compile("(?:^|.*/)" + antBody(prefix)
                + "([A-Za-z0-9_-]+)" + antBody(suffix) + "$");
    }

    /** Convert an Ant glob body to a regex body: {@code **}→any, {@code *}→non-slash, {@code ?}→one non-slash. */
    private static String antBody(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;   // consume the slash of **/
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public OperationResolver operations() {
        return operations;
    }

    public List<String> warnings() {
        return warnings;
    }

    /**
     * True when the scanned source uses the intercepted-UFW dispatcher — the SPL-Secure flavour,
     * auto-detected. Its APIs resolve to {@code send<command>Route} / {@code send<method>Route}.
     */
    public boolean isCommandDispatch() {
        return commandDispatch;
    }

    /** Bootstrap names available as scopes, sorted (e.g. ID, MY, SG, TH, VN). */
    public List<String> countries() {
        return new ArrayList<>(new TreeSet<>(countryToFiles.keySet()));
    }

    /** A registry containing every route in the tree (no country filter). */
    public RouteRegistry fullRegistry() {
        RouteRegistry registry = new RouteRegistry(properties);
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
        RouteRegistry registry = new RouteRegistry(properties);
        List<FileInfo> starts = new ArrayList<>();
        List<FileInfo> countrySpecific = countryToFiles.get(country);
        if (countrySpecific != null) {
            starts.addAll(countrySpecific);
        }
        // The default application.yml's country-less files (shared routes.xml, glob, literal list) are the
        // source of truth for what loads — always part of every country's scope.
        starts.addAll(sharedIncludeFiles);
        if (starts.isEmpty()) {
            scopeWarnings.add(countries().isEmpty()
                    ? "Country '" + country + "' matched no bootstrap: found no <country>.xml with a "
                            + "<camelContext>, and no application*.yml routes-include-pattern that resolves "
                            + "to any files. Check the source path/branch (and that the routes actually load)."
                    : "Unknown country '" + country + "'. Available bootstraps: " + countries());
            return registry;
        }
        Set<FileInfo> closure = closureOf(starts, country, scopeWarnings);
        // Dependency files are country- and version-agnostic shared/core routes (host resolution), not
        // country bootstraps — so they are ALWAYS in scope, even when the country bootstrap reaches them via
        // direct: rather than an <import>, so a host defined in a dependency resolves regardless of country.
        // But a dependency file that is NOT part of this country's assembly closure is AMBIENT: it stays
        // lookup-able for direct: hosts, yet must not contribute its routes as one of THIS country's
        // APIs/versions — otherwise another country's versioned route (e.g. R6.0_validate in a TH security
        // file) would wrongly become the predecessor of an SG API. A dependency file reached via the
        // closure (imported/ref'd) is a full country route and stays non-ambient.
        Set<FileInfo> ambientFiles = new java.util.LinkedHashSet<>(dependencyFiles);
        ambientFiles.removeAll(closure);
        Set<String> knownCountries = new HashSet<>();
        for (String c : countryToFiles.keySet()) {
            knownCountries.add(c.toLowerCase(java.util.Locale.ROOT));
        }
        int routeCount = 0;
        for (FileInfo f : closure) {                 // country routes first — they win any name clash
            // A closure file with no country of its own is a shared/common file — mark its routes so a diff
            // whose baseline resolves to one can tell the reviewer the predecessor is a shared route.
            boolean sharedFile = !knownCountries.isEmpty() && fileCountry(f, knownCountries) == null;
            for (RouteModel r : f.routes()) {
                registry.add(r, false, sharedFile);
                routeCount++;
            }
        }
        for (FileInfo f : ambientFiles) {            // dependency-only files — host lookup, not country APIs
            for (RouteModel r : f.routes()) {
                registry.add(r, true);
                routeCount++;
            }
        }
        if (routeCount == 0) {
            scopeWarnings.add("Country '" + country + "' resolved to no routes "
                    + "(check its <import>/<routeContextRef> targets are inside the source directory).");
        }
        return registry;
    }

    /** Transitive set of files reachable from the bootstrap file(s) via imports + context refs. */
    private Set<FileInfo> closureOf(java.util.Collection<FileInfo> starts, String country, List<String> scopeWarnings) {
        Set<FileInfo> visited = new HashSet<>();
        Deque<FileInfo> queue = new ArrayDeque<>(starts);
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
                // A bare/relative import (e.g. classpath:authCode.xml) can suffix-match the SAME file name
                // under several countries' dirs (sg/authCode.xml AND my/authCode.xml). At runtime only this
                // country's classpath entry wins; emulate that by dropping candidates that live under a
                // DIFFERENT country's subtree, so another country's versioned route never enters this scope.
                queue.addAll(preferCountry(targets, country));
            }
            for (String ref : f.metadata().contextRefs()) {
                List<FileInfo> defs = contextIdToFiles.get(ref);
                if (defs == null || defs.isEmpty()) {
                    scopeWarnings.add("Unresolved <routeContextRef ref=\"" + ref + "\"> in " + f.relPath());
                } else {
                    // The same id is often declared per-country; resolve to THIS country's definition (or a
                    // neutral shared one), never another country's — otherwise its routes leak into the scope.
                    queue.addAll(preferCountry(defs, country));
                }
            }
        }
        return visited;
    }

    /**
     * When an import suffix/wildcard-matched files that belong to several countries, keep only those for the
     * country being scoped (or those with no country at all — a genuinely shared file). If none belong to
     * this country the list is returned untouched, so a legitimately country-agnostic import is never dropped.
     * A single match is returned as-is (nothing to disambiguate).
     *
     * <p>A file's country is taken from a path segment ({@code sg/authCode.xml}) OR — for a flat {@code routes/}
     * dir where the country is only in the file name ({@code security-sg-9.14.xml} vs {@code security-th-9.14.xml})
     * — from a name token, but ONLY when a sibling candidate is the same file differing solely by that token
     * (proving the wildcard partitions by country). A lone {@code id-mapping.xml} keeps its country-like token
     * "id" untrusted, so it is never mistaken for the ID country and dropped.
     */
    private List<FileInfo> preferCountry(List<FileInfo> candidates, String country) {
        if (candidates.size() <= 1 || country == null) {
            return candidates;
        }
        Set<String> known = new HashSet<>();
        for (String c : countryToFiles.keySet()) {
            known.add(c.toLowerCase(java.util.Locale.ROOT));
        }
        if (known.isEmpty()) {
            return candidates;
        }
        String want = country.toLowerCase(java.util.Locale.ROOT);

        // Start from the strong signal: a country directory segment.
        Map<FileInfo, String> countryOf = new java.util.IdentityHashMap<>();
        for (FileInfo f : candidates) {
            countryOf.put(f, countrySegment(f, known));
        }
        // Add the weaker file-name signal, trusted only within a group of country-variant siblings: files whose
        // name is identical once the country token is blanked, carrying at least two DIFFERENT country tokens.
        Map<String, List<FileInfo>> byVariant = new java.util.LinkedHashMap<>();
        Map<FileInfo, String> nameToken = new java.util.IdentityHashMap<>();
        for (FileInfo f : candidates) {
            String[] nt = nameCountryToken(f, known);   // [normalisedName, token] or null
            if (nt != null) {
                nameToken.put(f, nt[1]);
                byVariant.computeIfAbsent(nt[0], k -> new ArrayList<>()).add(f);
            }
        }
        for (List<FileInfo> group : byVariant.values()) {
            Set<String> toks = new HashSet<>();
            for (FileInfo f : group) {
                toks.add(nameToken.get(f));
            }
            if (group.size() > 1 && toks.size() > 1) {           // a genuine per-country partition
                for (FileInfo f : group) {
                    countryOf.putIfAbsent(f, nameToken.get(f));   // fills only when no dir segment set it
                }
            }
        }

        List<FileInfo> ownOrNeutral = new ArrayList<>();
        for (FileInfo f : candidates) {
            String c = countryOf.get(f);
            if (c == null || c.equals(want)) {   // neutral (shared) or this country's own
                ownOrNeutral.add(f);
            }
        }
        return ownOrNeutral.isEmpty() ? candidates : ownOrNeutral;
    }

    /** The country a file belongs to — a path segment, else a name token — or null when it is shared/common. */
    private static String fileCountry(FileInfo f, Set<String> knownCountriesLower) {
        String seg = countrySegment(f, knownCountriesLower);
        if (seg != null) {
            return seg;
        }
        String[] nt = nameCountryToken(f, knownCountriesLower);
        return nt != null ? nt[1] : null;
    }

    /** The country a file belongs to, from a path segment matching a known country (case-insensitive), else null. */
    private static String countrySegment(FileInfo f, Set<String> knownCountriesLower) {
        for (String part : f.relPath().replace('\\', '/').split("/")) {
            String p = part.toLowerCase(java.util.Locale.ROOT);
            if (knownCountriesLower.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * If the file's base name carries a known-country token (delimited by {@code -}, {@code _} or {@code .}),
     * return {@code [normalisedName, token]} where the token is blanked to {@code *} — so country-variants of
     * one logical file share a normalised name. Returns null when the name holds no country token.
     */
    private static String[] nameCountryToken(FileInfo f, Set<String> knownCountriesLower) {
        String path = f.relPath().replace('\\', '/');
        String base = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        String[] toks = base.split("[^a-z0-9]+");
        for (int i = 0; i < toks.length; i++) {
            if (knownCountriesLower.contains(toks[i])) {
                String norm = base.replaceFirst("(?<![a-z0-9])" + java.util.regex.Pattern.quote(toks[i]) + "(?![a-z0-9])", "*");
                return new String[] { norm, toks[i] };
            }
        }
        return null;
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
