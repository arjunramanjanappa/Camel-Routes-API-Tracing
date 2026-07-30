package com.uob.tracer.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extracts the JSON keys from a request-body template (a {@code .ftl}/{@code .vm}
 * file) and diffs the key sets of two versions — the "Payload change" of the
 * release diff.
 *
 * <p>The templates are JSON in structure with FreeMarker/Velocity directives and
 * interpolations woven in, so they are NOT valid JSON until rendered. We therefore
 * strip the directives/interpolations/comments and scan the remaining skeleton for
 * {@code "key":} / {@code 'key':} pairs, tracking the enclosing object so a key can
 * be qualified as {@code Object.key} when the same name appears under more than one
 * object. Comparing the KEY SETS (not the raw text) makes the diff engine-agnostic:
 * a {@code .vm -> .ftl} migration with the same keys is not a change; only added /
 * removed keys are. {@code serviceVersionNumber} is excluded — it is reported
 * separately as the backend service-version bump.
 */
final class PayloadKeys {

    private PayloadKeys() {
    }

    /** A JSON key and the immediate object that contains it ("" for a root-level key). */
    record KeyRef(String parent, String name) {
    }

    /** A scalar key's value (raw expression text, whitespace-normalised) and its enclosing object. */
    record KeyValue(String parent, String name, String value) {
    }

    /** A scalar value the release changed for a key present on both sides: {@code key: before -> after}. */
    record ValueChange(String key, String before, String after) {
    }

    /** Keys added in the target vs lower, and keys removed (present in lower, gone in target). */
    record PayloadDiff(List<String> added, List<String> removed) {
        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private static final String SERVICE_VERSION = "serviceVersionNumber";

    /** Every JSON key in a template, with its enclosing object. */
    static List<KeyRef> extract(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        String s = stripNoise(template);
        List<KeyRef> out = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();   // enclosing object keys; absent = root
        String lastKey = null;                      // the key most recently seen (candidate object opener)
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;            // skip the escaped char
                    }
                    sb.append(s.charAt(j));
                    j++;
                }
                int k = j + 1;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k < n && s.charAt(k) == ':') {  // a quoted string followed by ':' is a key
                    String parent = stack.isEmpty() ? "" : stack.peek();
                    out.add(new KeyRef(parent, sb.toString()));
                    lastKey = sb.toString();
                    i = k + 1;
                } else {
                    i = j + 1;                       // a value string — ignore
                }
            } else if (c == '{') {
                stack.push(lastKey != null ? lastKey : "");   // the key that opened this object
                lastKey = null;
                i++;
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * Diff the key sets of the target vs the lower template(s). A name that appears
     * under more than one object (in either side) is qualified {@code Object.name}
     * so the two are distinguished; otherwise the flat name is used.
     */
    static PayloadDiff diff(List<KeyRef> target, List<KeyRef> lower) {
        Map<String, Set<String>> parentsByName = new HashMap<>();
        for (KeyRef r : concat(target, lower)) {
            if (isServiceVersion(r.name())) {
                continue;
            }
            parentsByName.computeIfAbsent(r.name(), k -> new HashSet<>()).add(r.parent());
        }
        Set<String> ambiguous = new HashSet<>();
        parentsByName.forEach((name, parents) -> {
            if (parents.size() > 1) {
                ambiguous.add(name);
            }
        });

        Set<String> tk = qualify(target, ambiguous);
        Set<String> lk = qualify(lower, ambiguous);
        List<String> added = new ArrayList<>(new TreeSet<>(diffSet(tk, lk)));
        List<String> removed = new ArrayList<>(new TreeSet<>(diffSet(lk, tk)));
        return new PayloadDiff(added, removed);
    }

    private static Set<String> qualify(List<KeyRef> refs, Set<String> ambiguous) {
        Set<String> out = new LinkedHashSet<>();
        for (KeyRef r : refs) {
            if (isServiceVersion(r.name())) {
                continue;
            }
            out.add(ambiguous.contains(r.name()) && !r.parent().isEmpty()
                    ? r.parent() + "." + r.name()
                    : r.name());
        }
        return out;
    }

    private static Set<String> diffSet(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static List<KeyRef> concat(List<KeyRef> a, List<KeyRef> b) {
        List<KeyRef> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static boolean isServiceVersion(String name) {
        return name != null && name.equalsIgnoreCase(SERVICE_VERSION);
    }

    /**
     * Every scalar key's VALUE (the raw expression text after {@code :}, whitespace-normalised), for the BAU
     * in-place payload diff. Unlike {@link #extract}, interpolations are PRESERVED (only comments stripped), so
     * a value like {@code ${ctx.amount}} is comparable — but note this is only meaningful when both sides are
     * the SAME template file/engine (which the BAU in-place git-diff guarantees; the cross-version diff, which
     * may pair a {@code .vm} against a {@code .ftl}, must stay key-based). Object/array values carry no scalar
     * and are skipped. {@code serviceVersionNumber} is excluded (it is the backend service-version bump).
     */
    static List<KeyValue> extractValues(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        String s = stripComments(template);
        List<KeyValue> out = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        String lastKey = null;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    sb.append(s.charAt(j));
                    j++;
                }
                int k = j + 1;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k < n && s.charAt(k) == ':') {   // quoted string + ':' = a key
                    lastKey = sb.toString();
                    i = k + 1;
                } else {                              // a quoted VALUE for the pending key
                    if (lastKey != null && !isServiceVersion(lastKey)) {
                        out.add(new KeyValue(stack.isEmpty() ? "" : stack.peek(), lastKey, normalize(sb.toString())));
                    }
                    lastKey = null;
                    i = j + 1;
                }
            } else if (c == '{') {
                stack.push(lastKey != null ? lastKey : "");
                lastKey = null;
                i++;
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                lastKey = null;
                i++;
            } else if (lastKey != null && c != ',' && c != ':' && c != '[' && c != ']' && !Character.isWhitespace(c)) {
                // An unquoted scalar value (e.g. ${ctx.amount}, $x.y, 123, true) — read to the enclosing ',' or '}'.
                int j = i;
                int depth = 0;
                StringBuilder sb = new StringBuilder();
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '{' || d == '[' || d == '(') {
                        depth++;
                    } else if (d == ')' || d == ']') {
                        depth--;
                    } else if (d == '}') {
                        if (depth == 0) {
                            break;   // the enclosing object's close
                        }
                        depth--;
                    } else if ((d == ',' || d == '\n') && depth == 0) {
                        break;
                    }
                    sb.append(d);
                    j++;
                }
                if (!isServiceVersion(lastKey)) {
                    out.add(new KeyValue(stack.isEmpty() ? "" : stack.peek(), lastKey, normalize(sb.toString())));
                }
                lastKey = null;
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /** For keys present on BOTH sides (matched by object+name), the ones whose scalar value changed. */
    static List<ValueChange> valueDiff(List<KeyValue> before, List<KeyValue> now) {
        Map<String, String> b = new LinkedHashMap<>();
        for (KeyValue kv : before) {
            b.put(qualified(kv), kv.value());
        }
        List<ValueChange> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KeyValue kv : now) {
            String key = qualified(kv);
            if (!seen.add(key)) {
                continue;   // one change per key
            }
            String was = b.get(key);
            if (was != null && !was.equals(kv.value())) {
                out.add(new ValueChange(key, was, kv.value()));
            }
        }
        out.sort(java.util.Comparator.comparing(ValueChange::key));
        return out;
    }

    private static String qualified(KeyValue kv) {
        return kv.parent().isEmpty() ? kv.name() : kv.parent() + "." + kv.name();
    }

    /** Collapse whitespace runs and trim, so a reindent / trailing-space edit is not a value change. */
    private static String normalize(String v) {
        return v == null ? "" : v.trim().replaceAll("\\s+", " ");
    }

    /** Strip only comments (keep interpolations/directives), for value comparison of the same template file. */
    private static String stripComments(String t) {
        return t
                .replaceAll("(?s)<#--.*?-->", " ")     // freemarker comments
                .replaceAll("(?s)#\\*.*?\\*#", " ")    // velocity block comments
                .replaceAll("(?m)##[^\\n]*", " ");     // velocity line comments
    }

    /** Remove comments, directives and interpolations so only the JSON skeleton remains. */
    private static String stripNoise(String t) {
        return t
                .replaceAll("(?s)<#--.*?-->", " ")              // freemarker comments
                .replaceAll("(?s)#\\*.*?\\*#", " ")             // velocity block comments
                .replaceAll("(?m)##[^\\n]*", " ")               // velocity line comments
                .replaceAll("(?s)<[/]?[#@][^>]*>", " ")         // freemarker directives <#..>, </#..>, <@..>
                .replaceAll("(?s)\\$!?\\{[^{}]*\\}", "_")       // ${..} / $!{..} interpolations -> a value token
                .replaceAll("(?m)#\\w+\\s*\\([^)]*\\)", " ")    // velocity #if(..)/#set(..)/#foreach(..)
                .replaceAll("(?m)#(end|else|stop|break)\\b", " "); // velocity bare directives
    }
}
