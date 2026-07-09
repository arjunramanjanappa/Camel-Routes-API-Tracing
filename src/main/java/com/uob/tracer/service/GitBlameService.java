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

    /** Distinct commit-author names for lines {@code [startLine, endLine]} of {@code file}, or empty. */
    public List<String> authors(Path file, int startLine, int endLine) {
        if (file == null || startLine < 1 || endLine < startLine) {
            return List.of();
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            return List.of();
        }
        try {
            // -w ignores whitespace churn, -C follows moved/copied code, so reformatting
            // commits don't steal attribution. --line-porcelain prints an "author <name>"
            // line per blamed line.
            ProcessBuilder pb = new ProcessBuilder("git", "-C", dir.toString(), "blame",
                    "-w", "-C", "-L", startLine + "," + endLine, "--line-porcelain",
                    "--", file.getFileName().toString());
            pb.redirectErrorStream(false);
            Process p = pb.start();

            Set<String> authors = new LinkedHashSet<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("author ")) {
                        String name = line.substring("author ".length()).trim();
                        if (!name.isEmpty()) {
                            authors.add(name);
                        }
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
            List<String> out = new ArrayList<>(authors);
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        } catch (Exception e) {
            return List.of();       // git missing / IO error / interrupted — degrade gracefully
        }
    }
}
