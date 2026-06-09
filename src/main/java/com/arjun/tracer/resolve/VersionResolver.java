package com.arjun.tracer.resolve;

import com.arjun.tracer.loader.RouteRegistry;

import java.util.List;

/**
 * Implements the framework's version-resolution rules:
 *
 * <ol>
 *   <li>empty version → BASE route ({@code operationName})</li>
 *   <li>otherwise pick the highest available {@code R<version>_<operation>}
 *       whose version is &le; the requested version (exact match, else fall back
 *       to lower minors: 9.4 → 9.3 → 9.2 → …)</li>
 *   <li>if no such route exists → BASE route ({@code operationName})</li>
 * </ol>
 */
public class VersionResolver {

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
