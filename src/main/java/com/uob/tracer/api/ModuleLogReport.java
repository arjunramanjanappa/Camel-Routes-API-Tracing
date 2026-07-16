package com.uob.tracer.api;

/**
 * One module's log-verification result in a multi-module release test. The uploaded log(s) are
 * correlated against each module's APIs (with that module's marker); a module that fails to analyse
 * carries an {@code error} instead of a {@code report} so the rest still return.
 *
 * @param name   the module's display name (pom artifactId / source label)
 * @param report the correlation result, or {@code null} if this module failed
 * @param error  the failure message, or {@code null} on success
 */
public record ModuleLogReport(String name, LogAnalysisReport report, String error) {
}
