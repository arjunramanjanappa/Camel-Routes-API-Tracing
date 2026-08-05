package com.uob.tracer.api;

/**
 * A validation finding on an impacted {@code .ftl} request-body template in the Release Impact diff.
 *
 * @param api     the API whose flow uses the template
 * @param file    the template file (short name / uri tail)
 * @param kind    {@code SYNTAX} — the template doesn't parse (unclosed directive, bad interpolation), or
 *                {@code STRUCTURE} — it parses but its rendered output isn't valid JSON (e.g. a missing comma)
 * @param message the parser / JSON error, one line
 * @param line    the 1-based line for a SYNTAX finding, or 0 when not known (STRUCTURE)
 */
public record TemplateIssue(String api, String file, String kind, String message, int line) {
}
