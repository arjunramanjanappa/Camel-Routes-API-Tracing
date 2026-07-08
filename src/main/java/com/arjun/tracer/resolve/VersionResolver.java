package com.arjun.tracer.resolve;

import com.arjun.tracer.loader.RouteRegistry;

import java.util.List;

/**
 * Implements the framework's version-resolution rules:
 *
 * <ol>
 *   <li>empty version → BASE route ({@code operationName})</li>
 *   <li>the {@link #isLatest(String) latest token} ({@code N/A} / {@code latest}) → the highest
 *       available {@code R<version>_<operation>}, or BASE if the repo has none (an unversioned repo
 *       whose routes never carry an {@code R<version>_} prefix)</li>
 *   <li>otherwise pick the highest available {@code R<version>_<operation>}
 *       whose version is &le; the requested version (exact match, else fall back
 *       to lower minors: 9.4 → 9.3 → 9.2 → …)</li>
 *   <li>if no such route exists → BASE route ({@code operationName})</li>
 * </ol>
 */
public class VersionResolver {

    /**
     * The special client-version token meaning "latest available per API, else the base route".
     * Typed as {@code N/A} in the UI; used to view an unversioned repo (routes with no
     * {@code R<version>_} prefix), which always falls through to the base route.
     */
    public static boolean isLatest(String version) {
        if (version == null) {
            return false;
        }
        String t = version.trim();
        return t.equalsIgnoreCase("N/A") || t.equalsIgnoreCase("NA") || t.equalsIgnoreCase("latest");
    }

    /**
     * @param routeName    the route to enter, e.g. {@code R9.3_fundTransferSubmitV2Api}
     *                     or BASE {@code fundTransferSubmitV2Api}
     * @param version      the version actually used, or null for BASE
     * @param baseFallback true when no versioned route matched and BASE was used
     */
    public record ResolvedRoute(String routeName, String version, boolean baseFallback) {
    }

    public ResolvedRoute resolve(RouteRegistry registry, String operationName, String requestedVersion) {
        if (requestedVersion == null || requestedVersion.isBlank()) {
            return new ResolvedRoute(operationName, null, true);
        }
        if (isLatest(requestedVersion)) {
            return resolveLatest(registry, operationName);
        }

        Version requested = Version.parse(requestedVersion.trim());
        List<String> available = registry.availableVersionsFor(operationName);

        Version best = null;
        for (String v : available) {
            Version candidate = Version.parse(v);
            if (candidate.compareTo(requested) <= 0) {           // only same-or-lower
                if (best == null || candidate.compareTo(best) > 0) {
                    best = candidate;                            // keep the highest
                }
            }
        }

        if (best != null) {
            return new ResolvedRoute("R" + best.text() + "_" + operationName, best.text(), false);
        }
        return new ResolvedRoute(operationName, null, true);
    }

    /**
     * The highest available {@code R<version>_<operation>} route, or the BASE route
     * ({@code operationName}) when the operation has no versioned route at all — the resolution for
     * the {@code N/A}/latest token, and the natural behaviour for an unversioned repo.
     */
    public ResolvedRoute resolveLatest(RouteRegistry registry, String operationName) {
        String latest = latestVersion(registry, operationName);
        if (latest != null) {
            return new ResolvedRoute("R" + latest + "_" + operationName, latest, false);
        }
        return new ResolvedRoute(operationName, null, true);
    }

    /** The highest release version present for this operation, or null when it has no versioned route. */
    public String latestVersion(RouteRegistry registry, String operationName) {
        Version best = null;
        for (String v : registry.availableVersionsFor(operationName)) {
            Version c = Version.parse(v);
            if (best == null || c.compareTo(best) > 0) {
                best = c;
            }
        }
        return best != null ? best.text() : null;
    }

    /**
     * The highest available release version strictly BELOW {@code target} for this
     * operation (versioned routes only — a BASE baseline is handled by the caller),
     * or null if there is none. Used by the release-diff to pick the immediate predecessor.
     */
    public String immediateLowerVersion(RouteRegistry registry, String operationName, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        Version t = Version.parse(target.trim());
        Version best = null;
        for (String v : registry.availableVersionsFor(operationName)) {
            Version c = Version.parse(v);
            if (c.compareTo(t) < 0 && (best == null || c.compareTo(best) > 0)) {
                best = c;
            }
        }
        return best != null ? best.text() : null;
    }

    /** A dotted numeric version, compared component-by-component. */
    record Version(int[] parts, String text) implements Comparable<Version> {

        static Version parse(String s) {
            String[] tokens = s.split("\\.");
            int[] p = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                try {
                    p[i] = Integer.parseInt(tokens[i].trim());
                } catch (NumberFormatException e) {
                    p[i] = 0;
                }
            }
            return new Version(p, s);
        }

        @Override
        public int compareTo(Version o) {
            int n = Math.max(parts.length, o.parts.length);
            for (int i = 0; i < n; i++) {
                int a = i < parts.length ? parts[i] : 0;
                int b = i < o.parts.length ? o.parts[i] : 0;
                if (a != b) {
                    return Integer.compare(a, b);
                }
            }
            return 0;
        }
    }
}
