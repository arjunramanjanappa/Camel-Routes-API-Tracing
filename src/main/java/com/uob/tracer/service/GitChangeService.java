package com.uob.tracer.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the files a release changed, where a "release" is identified by the app/commit version token in
 * the commit message ({@code [jira][country][19.18.0]-message}, position may vary). Shells out to the
 * git CLI (like {@link GitBlameService}) and degrades gracefully to "no changes" if git is missing or
 * the source isn't a work tree.
 *
 * <p>Whitespace-only changes are ignored ({@code -w}); the net effect from before the release to HEAD is
 * used, so reverts within the release don't count. Renames are followed ({@code -M}).
 */
public class GitChangeService {

    /** Files (repo-relative, forward-slashed) the release changed, and how many commits matched its version. */
    public record ReleaseChanges(Set<String> changedFiles, int matchedCommits, boolean gitAvailable) {
        public static ReleaseChanges none() {
            return new ReleaseChanges(Set.of(), 0, false);
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern VERSIONISH = Pattern.compile("\\d+(?:\\.\\d+)*");

    public ReleaseChanges changedFor(Path repoDir, String appVersion) {
        if (repoDir == null || appVersion == null || appVersion.isBlank()) {
            return ReleaseChanges.none();
        }
        String wanted = normalize(appVersion);
        if (wanted == null) {
            return ReleaseChanges.none();
        }

        // 1. Every commit as hash | timestamp | subject. The version token lives in the commit subject
        //    (first line), so %s keeps each commit on exactly one line — no separator gymnastics needed.
        List<String> log = run(repoDir, 15, "log", "--no-merges", "--format=%H|%ct|%s");
        if (log == null) {
            return ReleaseChanges.none();   // not a work tree / git missing
        }
        List<String> matched = new ArrayList<>();
        long earliestTs = Long.MAX_VALUE;
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
            if (messageMatches(f[2], wanted)) {
                matched.add(f[0].trim());
                long ts;
                try {
                    ts = Long.parseLong(f[1].trim());
                } catch (NumberFormatException e) {
                    ts = 0;
                }
                if (ts < earliestTs) {
                    earliestTs = ts;
                    earliest = f[0].trim();
                }
            }
        }
        if (matched.isEmpty()) {
            return new ReleaseChanges(Set.of(), 0, true);
        }

        // 2. Candidate files: non-whitespace changes across the matched commits (one git show, all hashes).
        Set<String> candidates = new LinkedHashSet<>();
        List<String> showArgs = new ArrayList<>(List.of("show", "--format=", "-w", "-M", "--numstat"));
        showArgs.addAll(matched);
        List<String> stat = run(repoDir, 30, showArgs.toArray(new String[0]));
        if (stat != null) {
            for (String line : stat) {
                String[] c = line.split("\t");
                if (c.length < 3) {
                    continue;
                }
                boolean real = !"0".equals(c[0].trim()) || !"0".equals(c[1].trim());   // "-"/"-" = binary → changed
                if (real) {
                    candidates.add(renamedTarget(c[2]));
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
        return new ReleaseChanges(candidates, matched.size(), true);
    }

    private static boolean messageMatches(String body, String wanted) {
        Matcher m = BRACKET.matcher(body);
        while (m.find()) {
            String tok = m.group(1).trim();
            if (VERSIONISH.matcher(tok).matches() && wanted.equals(normalize(tok))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop trailing-zero segments so {@code 19.18.0} ≡ {@code 19.18}, while {@code 19.8} stays distinct
     * from {@code 19.18}. Returns null if there's no dotted-number version in the string.
     */
    static String normalize(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = VERSIONISH.matcher(v.trim());
        if (!m.find()) {
            return null;
        }
        String[] parts = m.group().split("\\.");
        int end = parts.length;
        while (end > 1 && parts[end - 1].chars().allMatch(ch -> ch == '0')) {
            end--;   // strip trailing all-zero segments (19.18.0 -> 19.18)
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(Integer.parseInt(parts[i]));   // normalise 08 -> 8
        }
        return sb.toString();
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
