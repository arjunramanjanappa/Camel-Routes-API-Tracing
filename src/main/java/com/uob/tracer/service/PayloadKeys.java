package com.uob.tracer.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
