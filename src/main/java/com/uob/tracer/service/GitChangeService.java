package com.uob.tracer.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the files a release changed, where a "release" is identified by the app/commit version token(s) in
 * the commit message ({@code [jira][country][19.18.0]-message}, position may vary). One or more versions may
 * be given (comma/space-separated) and are matched <b>literally</b> — {@code 19.10}, {@code 19.10.0} and
 * {@code 19.10.1} are distinct (the exact Jira version used to commit). Shells out to the git CLI (like
 * {@link GitBlameService}) and degrades gracefully to "no changes" if git is missing or the source isn't a
 * work tree.
 *
 * <p>Whitespace-only changes are ignored ({@code -w}); the net effect from before the release to HEAD is
 * used, so reverts within the release don't count. Renames are followed ({@code -M}).
 */
public class GitChangeService {

    /**
     * Files (repo-relative, forward-slashed) the release changed, how many commits matched, the distinct
     * commit authors who changed each file (for "who to ask"), and — for the per-version breakdown — which
     * of the requested version(s) changed each file.
     */
    public record ReleaseChanges(Set<String> changedFiles, int matchedCommits, boolean gitAvailable,
                                 Map<String, List<String>> fileAuthors,
                                 Map<String, List<String>> fileVersions) {
        public static ReleaseChanges none() {
            return new ReleaseChanges(Set.of(), 0, false, Map.of(), Map.of());
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");

    public ReleaseChanges changedFor(Path repoDir, String appVersion) {
        if (repoDir == null) {
            return ReleaseChanges.none();
        }
        // One or more versions, comma/space-separated, matched literally. Insertion order is kept so the
        // per-file version list reads in the order the user entered them.
        Set<String> wanted = parseVersions(appVersion);
        if (wanted.isEmpty()) {
            return ReleaseChanges.none();
        }

        // 1. Every commit as hash | timestamp | subject. The version token lives in the commit subject
        //    (first line), so %s keeps each commit on exactly one line — no separator gymnastics needed.
        List<String> log = run(repoDir, 15, "log", "--no-merges", "--format=%H|%ct|%s");
        if (log == null) {
            return ReleaseChanges.none();   // not a work tree / git missing
        }
        List<String> matched = new ArrayList<>();
        Map<String, List<String>> commitVersions = new LinkedHashMap<>();   // hash -> the requested version(s) it carries
        // git log is newest-first (and never lists a parent before its child), so the LAST matched commit we
        // see is the oldest — a robust pre-release baseline even when several commits share a timestamp
        // (which timestamp comparison would tie-break wrongly).
        String earliest = null;
        for (String rec : log) {
            String r = rec.strip();
            if (r.isEmpty()) {
                continue;
            }
            String[] f = r.split("\\|", 3);   // hash | timestamp | subject
            if (f.length < 3) {
                continue;
            }
            List<String> vs = versionsIn(f[2], wanted);
            if (!vs.isEmpty()) {
                String hash = f[0].trim();
                matched.add(hash);
                commitVersions.put(hash, vs);
                earliest = hash;   // overwritten each match → ends on the oldest matched commit
            }
        }
        if (matched.isEmpty()) {
            return new ReleaseChanges(Set.of(), 0, true, Map.of(), Map.of());   // no commit matched any version
        }

        // 2. Candidate files + their authors + the version(s) that touched them: non-whitespace changes across
        //    the matched commits (one git show). The "@@@<hash>|<author>" format line precedes each commit's
        //    numstat block, so files are attributed to the matched commit (its author and its version) that
        //    changed them.
        Set<String> candidates = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> authors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> versions = new LinkedHashMap<>();
        List<String> showArgs = new ArrayList<>(List.of("show", "--format=@@@%H|%an", "-w", "-M", "--numstat"));
        showArgs.addAll(matched);
        List<String> stat = run(repoDir, 30, showArgs.toArray(new String[0]));
        if (stat != null) {
            String curAuthor = null;
            List<String> curVersions = List.of();
            for (String line : stat) {
                if (line.startsWith("@@@")) {
                    String rest = line.substring(3);
                    int bar = rest.indexOf('|');
                    String hash = (bar >= 0 ? rest.substring(0, bar) : rest).trim();
                    curAuthor = bar >= 0 ? rest.substring(bar + 1).trim() : null;
                    curVersions = commitVersions.getOrDefault(hash, List.of());
                    continue;
                }
                String[] c = line.split("\t");
                if (c.length < 3) {
                    continue;
                }
                boolean real = !"0".equals(c[0].trim()) || !"0".equals(c[1].trim());   // "-"/"-" = binary → changed
                if (real) {
                    String file = renamedTarget(c[2]);
                    candidates.add(file);
                    if (curAuthor != null && !curAuthor.isEmpty()) {
                        authors.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(curAuthor);
                    }
                    if (!curVersions.isEmpty()) {
                        versions.computeIfAbsent(file, k -> new LinkedHashSet<>()).addAll(curVersions);
                    }
                }
            }
        }

        // 3. Net check: keep only files that still differ (ignoring whitespace) from just before the release to HEAD.
        if (earliest != null) {
            List<String> net = run(repoDir, 15, "diff", "-w", "-M", "--name-only", earliest + "^", "HEAD");
            if (net != null) {
                Set<String> netSet = new LinkedHashSet<>();
                for (String n : net) {
                    if (!n.isBlank()) {
                        netSet.add(n.trim().replace('\\', '/'));
                    }
                }
                candidates.retainAll(netSet);
            }
        }
        Map<String, List<String>> fileAuthors = new LinkedHashMap<>();
        Map<String, List<String>> fileVersions = new LinkedHashMap<>();
        for (String f : candidates) {
            LinkedHashSet<String> a = authors.get(f);
            if (a != null && !a.isEmpty()) {
                fileAuthors.put(f, new ArrayList<>(a));
            }
            LinkedHashSet<String> v = versions.get(f);
            if (v != null && !v.isEmpty()) {
                fileVersions.put(f, orderedByRequest(v, wanted));
            }
        }
        return new ReleaseChanges(candidates, matched.size(), true, fileAuthors, fileVersions);
    }

    /** Split the field into the distinct version tokens the user entered (comma/whitespace-separated), trimmed. */
    static Set<String> parseVersions(String field) {
        Set<String> out = new LinkedHashSet<>();
        if (field == null) {
            return out;
        }
        for (String part : field.trim().split("[,\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** The requested version(s) that appear as a literal {@code [token]} in the commit subject, in subject order. */
    private static List<String> versionsIn(String subject, Set<String> wanted) {
        List<String> found = new ArrayList<>();
        Matcher m = BRACKET.matcher(subject);
        while (m.find()) {
            String tok = m.group(1).trim();
            if (wanted.contains(tok) && !found.contains(tok)) {
                found.add(tok);   // literal, exact match: 19.10 != 19.10.0 != 19.10.1
            }
        }
        return found;
    }

    /** Order the versions of one file by the order the user requested them (stable, readable badges). */
    private static List<String> orderedByRequest(Set<String> got, Set<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            if (got.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** numstat rename forms: {@code old => new} or {@code dir/{old => new}/file} → the new path (forward-slashed). */
    private static String renamedTarget(String path) {
        String p = path.trim();
        int lb = p.indexOf('{');
        int rb = p.indexOf('}');
        if (lb >= 0 && rb > lb && p.substring(lb, rb).contains(" => ")) {
            String mid = p.substring(lb + 1, rb);
            String newMid = mid.substring(mid.indexOf(" => ") + 4);
            p = (p.substring(0, lb) + newMid + p.substring(rb + 1)).replace("//", "/");
        } else {
            int arrow = p.indexOf(" => ");
            if (arrow >= 0) {
                p = p.substring(arrow + 4);
            }
        }
        return p.trim().replace('\\', '/');
    }

    private List<String> run(Path dir, int timeoutSec, String... gitArgs) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
            cmd.addAll(List.of(gitArgs));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            List<String> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.add(line);
                }
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}
