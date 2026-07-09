package com.uob.tracer.scan;

/**
 * A Camel {@code routes-include-pattern} found in an {@code application*.yml/.yaml/.properties} file
 * — the second way a country bootstrap is discovered (used only when the filename way, {@code SG.xml}
 * + {@code <camelContext>}, finds nothing).
 *
 * @param profile the Spring profile from an {@code application-<profile>.yml} filename (the country),
 *                or null for a plain {@code application.yml}
 * @param pattern the raw pattern value, e.g. {@code classpath:routes/secure-${country:}.xml} (a
 *                direct file, or one carrying a {@code ${country}} placeholder)
 */
public record RouteIncludePattern(String profile, String pattern) {
}
