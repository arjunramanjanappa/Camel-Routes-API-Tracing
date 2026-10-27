package io.traceguard.service;

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
import java.util.regex.Pattern;

/**
 * Resolves the authors of a line range of a file via {@code git blame}. Used by the
 * release-diff to attribute a changed route to whoever authored its lines in the
 * <em>latest</em> version (the lower/BAU version is not blamed).
 *
 * <p>Best-effort and side-effect free: if the source directory is not a git work
 * tree, git is not installed, or the file is untracked, it returns an empty list so
 * the report simply omits the attribution rather than failing.
 */
public class GitBlameService {

    /** A blame header line: {@code <40-hex hash> <orig line> <final line> [<group size>]}. */
    private static final Pattern HEADER = Pattern.compile("^[0-9a-f]{40} \\d+ \\d+( \\d+)?$");

    /** One commit that authored lines in the blamed range: hash, author and the commit subject. */
    public record BlameCommit(String hash, String author, String summary) {
    }

    // Blame is deliberately never cached (the repo keeps receiving check-ins and attribution must stay live), but
    // it IS counted, so a slow Compare can be explained from the log.
    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();

    /** Blame activity since the last reset: processes run and the time spent in them. */
    public record Stats(long calls, long millis) {
    }

    public Stats statsAndReset() {
        return new Stats(calls.getAndSet(0), TimeUnit.NANOSECONDS.toMillis(nanos.getAndSet(0)));
    }

    /** Distinct commit-author names for lines {@code [startLine, endLine]} of {@code file}, or empty. */
    public List<String> authors(Path file, int startLine, int endLine) {
        Set<String> authors = new LinkedHashSet<>();
        for (BlameCommit c : commits(file, startLine, endLine)) {
            if (!c.author().isEmpty()) {
                authors.add(c.author());
            }
        }
        List<String> out = new ArrayList<>(authors);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * The distinct commits that authored lines {@code [startLine, endLine]} of {@code file}, in first-seen order,
     * each with its author and subject — so a caller can read the Jira ticket and message off the subject.
     * Empty when the file is not in a git work tree, git is missing, or the range is invalid.
     */
    public List<BlameCommit> commits(Path file, int startLine, int endLine) {
        if (file == null || startLine < 1 || endLine < startLine) {
            return List.of();
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            return List.of();
        }
        long t0 = System.nanoTime();
        try {
            return blame(dir, file, startLine, endLine);
        } finally {
            calls.incrementAndGet();
            nanos.addAndGet(System.nanoTime() - t0);
        }
    }

    private static List<BlameCommit> blame(Path dir, Path file, int startLine, int endLine) {
        try {
            // -w ignores whitespace churn, -C follows moved/copied code, so reformatting
            // commits don't steal attribution. --line-porcelain prints the full header
            // ("author <name>", "summary <subject>", …) for EVERY blamed line.
            ProcessBuilder pb = new ProcessBuilder("git", "-C", dir.toString(), "blame",
                    "-w", "-C", "-L", startLine + "," + endLine, "--line-porcelain",
                    "--", file.getFileName().toString());
            pb.redirectErrorStream(false);
            Process p = pb.start();

            Map<String, String[]> byHash = new LinkedHashMap<>();   // hash -> {author, summary}
            String cur = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (HEADER.matcher(line).matches()) {
                        cur = line.substring(0, 40);
                        byHash.putIfAbsent(cur, new String[]{"", ""});
                    } else if (cur != null && line.startsWith("author ")) {
                        byHash.get(cur)[0] = line.substring("author ".length()).trim();
                    } else if (cur != null && line.startsWith("summary ")) {
                        byHash.get(cur)[1] = line.substring("summary ".length()).trim();
                    }
                }
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return List.of();
            }
            if (p.exitValue() != 0) {
                return List.of();   // not a repo, untracked file, bad range — no attribution
            }
            List<BlameCommit> out = new ArrayList<>();
            for (var e : byHash.entrySet()) {
                out.add(new BlameCommit(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
            return out;
        } catch (Exception e) {
            return List.of();       // git missing / IO error / interrupted — degrade gracefully
        }
    }
}
