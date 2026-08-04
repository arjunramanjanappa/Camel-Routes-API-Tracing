package com.uob.tracer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 3-way merge of a config JSON object keyed by top-level entry (app name → rules / module list), used to
 * push corrected/updated bundled defaults to existing users WITHOUT clobbering their own edits.
 *
 * <p>The three inputs are the git-style base / ours / theirs:
 * <ul>
 *   <li><b>base</b> — the seed the package last applied (the recorded baseline).</li>
 *   <li><b>ours</b> — the user's current config (their live edits during testing).</li>
 *   <li><b>theirs</b> — the seed bundled in the new package.</li>
 * </ul>
 *
 * <p>Per top-level key, the merge is deliberately conservative — it never deletes a user's data:
 * <ul>
 *   <li>A key the seed <b>added</b> (not in base) and the user doesn't have → added.</li>
 *   <li>A key the user has and <b>hasn't touched</b> (ours == base) → updated to the new seed value
 *       (this is how a corrected default reaches existing users).</li>
 *   <li>A key the user <b>edited</b> (ours != base) → kept as-is (the user always wins).</li>
 *   <li>A key the user <b>deleted</b> (in base, absent from ours) → left deleted (not resurrected).</li>
 *   <li>A key the seed no longer ships → left untouched in the user's config (never removed — a mistaken
 *       omission in a build must not wipe a user's setup).</li>
 * </ul>
 */
final class SeedMerge {

    private SeedMerge() {
    }

    /**
     * Remove top-level "comment" keys — those starting with {@code _} — from a JSON object in place. JSON
     * has no comment syntax, so the config files use an {@code "_comment"} key to document their format; it
     * must be ignored on read (and never fed to the typed binder, which would choke on its string value).
     * No-op for a non-object node.
     */
    static void stripCommentKeys(JsonNode tree) {
        if (tree instanceof ObjectNode obj) {
            List<String> remove = new ArrayList<>();
            for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (k.startsWith("_")) {
                    remove.add(k);
                }
            }
            remove.forEach(obj::remove);
        }
    }

    /** True when two JSON documents are structurally equal (whitespace / key-order independent). */
    static boolean jsonEquals(byte[] a, byte[] b, ObjectMapper mapper) {
        if (a == null || b == null) {
            return a == b;
        }
        try {
            return mapper.readTree(a).equals(mapper.readTree(b));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Merge the bundled {@code theirs} seed into the user's {@code ours} config given the last-applied
     * {@code base}. Returns the merged JSON bytes, or {@code ours} unchanged if anything isn't a JSON object
     * (defensive — we never rewrite a config we can't reason about). A null base is treated as empty, so on
     * the first merge-enabled upgrade only brand-new keys are added and existing keys are left to the user.
     */
    static byte[] merge(byte[] base, byte[] ours, byte[] theirs, ObjectMapper mapper) {
        try {
            JsonNode baseNode = base == null ? mapper.createObjectNode() : mapper.readTree(base);
            JsonNode oursNode = mapper.readTree(ours);
            JsonNode theirsNode = mapper.readTree(theirs);
            if (!baseNode.isObject() || !oursNode.isObject() || !theirsNode.isObject()) {
                return ours;   // not the keyed-object shape we merge — leave the user's file alone
            }
            ObjectNode baseObj = (ObjectNode) baseNode;
            ObjectNode oursObj = (ObjectNode) oursNode;
            ObjectNode theirsObj = (ObjectNode) theirsNode;
            ObjectNode result = oursObj.deepCopy();

            for (Iterator<String> it = theirsObj.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                JsonNode theirsVal = theirsObj.get(key);
                boolean userHas = oursObj.has(key);
                boolean baseHas = baseObj.has(key);
                if (!userHas) {
                    // The user doesn't have this key. Add it ONLY if it's a brand-new default — if it was in
                    // the base, the user deleted it on purpose, so honour that and don't resurrect it.
                    if (!baseHas) {
                        result.set(key, theirsVal);
                    }
                } else {
                    // The user has this key: update it only if they never touched it (still equal to base).
                    boolean untouched = baseHas && oursObj.get(key).equals(baseObj.get(key));
                    if (untouched) {
                        result.set(key, theirsVal);
                    }
                    // else the user edited it — keep theirs... i.e. keep OURS. The user always wins.
                }
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        } catch (Exception e) {
            return ours;   // any parse trouble → never risk the user's config
        }
    }
}
