package com.uob.tracer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit + integration coverage for {@link GitChangeService}: version-token normalisation and, when a git
 * CLI is available, the end-to-end "what did this release change" detection (version-token matching,
 * whitespace-insensitive net diff, and graceful degradation on a non-git directory).
 */
class GitChangeServiceTest {

    // --- normalise: 19.18.0 ≡ 19.18, but 19.8 ≠ 19.18 (no git needed) ---

    @Test
    void trailingZeroSegmentsAreDropped() {
        assertThat(GitChangeService.normalize("19.18.0")).isEqualTo("19.18");
        assertThat(GitChangeService.normalize("19.18")).isEqualTo("19.18");
        assertThat(GitChangeService.normalize("19.18.0.0")).isEqualTo("19.18");
    }

    @Test
    void differentMinorsStayDistinct() {
        assertThat(GitChangeService.normalize("19.8")).isNotEqualTo(GitChangeService.normalize("19.18"));
        assertThat(GitChangeService.normalize("19.8")).isEqualTo("19.8");
    }

    @Test
    void nonVersionStringNormalisesToNull() {
        assertThat(GitChangeService.normalize("no-version-here")).isNull();
        assertThat(GitChangeService.normalize(null)).isNull();
    }

    // --- a non-git directory degrades to "unavailable", never throws ---

    @Test
    void plainDirectoryIsNotAWorkTree(@TempDir Path dir) {
        GitChangeService.ReleaseChanges rc = new GitChangeService().changedFor(dir, "19.18.0");
        assertThat(rc.gitAvailable()).isFalse();
        assertThat(rc.changedFiles()).isEmpty();
    }

    @Test
    void blankAppVersionIsANoOp(@TempDir Path dir) {
        assertThat(new GitChangeService().changedFor(dir, "  ").gitAvailable()).isFalse();
        assertThat(new GitChangeService().changedFor(dir, null).gitAvailable()).isFalse();
    }

    // --- end-to-end against a real temp git repo (skipped when git is unavailable) ---

    @Test
    void detectsFilesChangedByTheReleaseVersion(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        Files.writeString(dir.resolve("Bar.java"), "class Bar { int b = 1; }\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // A 19.18.0 release changes Foo (real change) but only reformats Bar (whitespace only).
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 2; }\n");
        Files.writeString(dir.resolve("Bar.java"), "class   Bar   {   int b = 1;   }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change Foo, reformat Bar");

        GitChangeService.ReleaseChanges rc = new GitChangeService().changedFor(dir, "19.18.0");

        assertThat(rc.gitAvailable()).isTrue();
        assertThat(rc.matchedCommits()).isEqualTo(1);
        assertThat(rc.changedFiles()).contains("Foo.java");
        assertThat(rc.changedFiles()).doesNotContain("Bar.java");   // whitespace-only change ignored
    }

    @Test
    void appVersionMatchesRegardlessOfTrailingZero(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 2; }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change Foo");

        // The commit tags 19.18.0; asking for the normalised 19.18 must still match.
        GitChangeService.ReleaseChanges rc = new GitChangeService().changedFor(dir, "19.18");
        assertThat(rc.matchedCommits()).isEqualTo(1);
        assertThat(rc.changedFiles()).contains("Foo.java");
    }

    @Test
    void unmatchedVersionChangesNothing(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 2; }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change Foo");

        GitChangeService.ReleaseChanges rc = new GitChangeService().changedFor(dir, "19.99.0");
        assertThat(rc.gitAvailable()).isTrue();
        assertThat(rc.matchedCommits()).isZero();
        assertThat(rc.changedFiles()).isEmpty();
    }

    // --- git test helpers ---

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void initRepo(Path dir) throws Exception {
        git(dir, "init");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "add", "-A");
        git(dir, "commit", "-m", message);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
