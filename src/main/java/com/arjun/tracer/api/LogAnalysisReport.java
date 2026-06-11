package com.arjun.tracer.api;

import java.util.List;

/**
 * Result of analysing an uploaded log / Splunk export against the traced APIs.
 *
 * @param uploadType    detected input kind: RAW_LOG or SPLUNK
 * @param clientVersion the client release the analysis was scoped to
 * @param country       bootstrap scope, if any
 * @param linesScanned  total lines read from the file
 * @param matchedLines  lines that matched a MightyMessage/MightyHostMessage pattern
 * @param transactions  distinct correlation ids reconstructed
 * @param unparsedLines marker lines that could not be parsed (surfaced for investigation)
 * @param apis          per-API correlation results
 * @param warnings      non-fatal notes (e.g. detection fell back, fields missing)
 */
public record LogAnalysisReport(
        String uploadType,
        String clientVersion,
        String country,
        int linesScanned,
        int matchedLines,
        int transactions,
        int unparsedLines,
        List<ApiLogResult> apis,
        List<String> warnings) {
}
