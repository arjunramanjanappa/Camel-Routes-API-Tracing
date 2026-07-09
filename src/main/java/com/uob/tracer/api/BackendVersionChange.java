package com.uob.tracer.api;

/**
 * A backend whose resolved service version (the {@code serviceVersionNumber} read
 * from its framework request template) changed between the immediate-lower version
 * and the target release — e.g. {@code 2.2 → 2.3}. This lives in the template file,
 * not the route XML, so it is detected by comparing the traced backend versions of
 * the two flows rather than by the structural route diff.
 *
 * @param backend     the backend api value (the call's identity)
 * @param fromVersion the service version at the immediate-lower version (null if none)
 * @param toVersion   the service version at the target release (null if none)
 */
public record BackendVersionChange(String backend, String fromVersion, String toVersion) {
}
