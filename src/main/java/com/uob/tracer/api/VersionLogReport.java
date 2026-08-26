package com.uob.tracer.api;

import java.util.List;

/**
 * One requested version's multi-module log correlation, for the Release Impact "upload once, correlate against
 * every version" flow. The uploaded log is spooled once by the servlet and re-read per version, so a large log
 * is NOT re-uploaded per version (mirrors how {@link ModuleLogReport} re-reads it per module).
 *
 * @param version the version this result is for, echoed back from the request (e.g. "9.18" or "BASE") so the
 *                caller can map each result to its version
 * @param modules the per-module correlation for that version
 */
public record VersionLogReport(String version, List<ModuleLogReport> modules) {
}
