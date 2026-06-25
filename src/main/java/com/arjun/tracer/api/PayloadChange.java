package com.arjun.tracer.api;

import java.util.List;

/**
 * The change in an API's request payload between the target release and its
 * immediate-lower version, as the set of JSON keys added / removed across the
 * request-body templates ({@code .ftl}/{@code .vm}) the flow uses.
 *
 * <p>Key-based and engine-agnostic: a {@code .vm → .ftl} migration with the same keys
 * is no change; {@code serviceVersionNumber} is excluded (reported as the backend
 * service-version bump). A key whose name appears under more than one object is
 * qualified {@code Object.key}.
 *
 * @param addedKeys   JSON keys present in the target but not the lower version
 * @param removedKeys JSON keys present in the lower version but not the target
 */
public record PayloadChange(List<String> addedKeys, List<String> removedKeys) {
}
