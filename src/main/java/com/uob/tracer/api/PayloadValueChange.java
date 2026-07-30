package com.uob.tracer.api;

/**
 * A scalar payload value the release changed in place for a key that exists on both sides — {@code key:
 * before → after}. Detected only on a BAU route's OWN template git-diffed against its pre-release self, so both
 * sides are the same template file/engine (the value expressions are directly comparable). {@code before}/
 * {@code after} are the raw, whitespace-normalised value tokens (e.g. {@code ${ctx.legacyAmount}}).
 *
 * @param key    the (optionally object-qualified) payload key whose value changed
 * @param before the value the BAU template sent before the release
 * @param after  the value it sends after the release
 */
public record PayloadValueChange(String key, String before, String after) {
}
