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
 * Unit + integration coverage for {@link GitChangeService}: version-token parsing (exact, multi-value) and,
 * when a git CLI is available, the end-to-end "what did this release change" detection — exact version-token
 * matching, per-version attribution, whitespace-insensitive net diff, and graceful non-git degradation.
 */
class GitChangeServiceTest {

    // --- parse: comma/space-separated, exact tokens, no normalisation (no git needed) ---

    @Test
    void parsesMultipleVersionsExactlyAsEntered() {
        assertThat(GitChangeService.parseVersions("19.10, 19.10.0, 19.10.1 , 19.18.0"))
                .containsExactly("19.10", "19.10.0", "19.10.1", "19.18.0");   // distinct, order preserved
        assertThat(GitChangeService.parseVersions("19.18.0")).containsExactly("19.18.0");
    }

    @Test
    void parseIgnoresBlankAndSplitsOnWhitespaceOrComma() {
        assertThat(GitChangeService.parseVersions("  19.10   19.18.0 ,, ")).containsExactly("19.10", "19.18.0");
        assertThat(GitChangeService.parseVersions("  ")).isEmpty();
        assertThat(GitChangeService.parseVersions(null)).isEmpty();
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
    void versionMatchIsExactNoTrailingZeroEquivalence(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 2; }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change Foo");

        // The commit tags exactly 19.18.0. Asking for 19.18 must NOT match (distinct Jira versions).
        assertThat(new GitChangeService().changedFor(dir, "19.18").matchedCommits()).isZero();
        // Asking for the exact 19.18.0 matches.
        GitChangeService.ReleaseChanges exact = new GitChangeService().changedFor(dir, "19.18.0");
        assertThat(exact.matchedCommits()).isEqualTo(1);
        assertThat(exact.changedFiles()).contains("Foo.java");
    }

    @Test
    void multipleVersionsUnionWithPerFileAttribution(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        Files.writeString(dir.resolve("Baz.java"), "class Baz { int c = 1; }\n");
        commit(dir, "[JIRA-1][SG][19.9.0] baseline");
        Files.writeString(dir.resolve("Foo.java"), "class Foo { int a = 2; }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change Foo");
        Files.writeString(dir.resolve("Baz.java"), "class Baz { int c = 2; }\n");
        commit(dir, "[JIRA-3][SG][19.10.1] change Baz");

        // Both listed versions are matched; changed files are the union, attributed per version.
        GitChangeService.ReleaseChanges rc = new GitChangeService().changedFor(dir, "19.18.0, 19.10.1");
        assertThat(rc.matchedCommits()).isEqualTo(2);
        assertThat(rc.changedFiles()).contains("Foo.java", "Baz.java");
        assertThat(rc.fileVersions().get("Foo.java")).containsExactly("19.18.0");
        assertThat(rc.fileVersions().get("Baz.java")).containsExactly("19.10.1");
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

    @Test
    void perFileSpanBoundsTheDiffToTheReleasesOwnCommits(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        // Baseline template: fieldA only.
        Files.writeString(dir.resolve("enquiry.ftl"), "{ \"fieldA\": \"${a}\" }\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");
        // The 19.18.0 release ADDS fieldB — this is the only change that should count.
        Files.writeString(dir.resolve("enquiry.ftl"), "{ \"fieldA\": \"${a}\", \"fieldB\": \"${b}\" }\n");
        commit(dir, "[JIRA-2][SG][19.18.0] add fieldB");
        // A LATER, unrelated commit (NOT the release) adds fieldC. It must not leak into the release's diff.
        Files.writeString(dir.resolve("enquiry.ftl"),
                "{ \"fieldA\": \"${a}\", \"fieldB\": \"${b}\", \"fieldC\": \"${c}\" }\n");
        commit(dir, "[JIRA-3][SG][19.20.0] add fieldC after the release");

        GitChangeService svc = new GitChangeService();
        GitChangeService.ReleaseChanges rc = svc.changedFor(dir, "19.18.0");
        assertThat(rc.changedFiles()).contains("enquiry.ftl");

        String before = String.join("\n", svc.fileAtRef(dir, rc.beforeRefFor("enquiry.ftl"), "enquiry.ftl"));
        String after = String.join("\n", svc.fileAtRef(dir, rc.afterRefFor("enquiry.ftl"), "enquiry.ftl"));
        // before = the release's own parent: fieldA only (no fieldB yet).
        assertThat(before).contains("fieldA").doesNotContain("fieldB").doesNotContain("fieldC");
        // after = the release's own commit: fieldA + fieldB — but NOT the later fieldC. The diff is exactly the
        // field the release added, never the unrelated post-release edit.
        assertThat(after).contains("fieldA").contains("fieldB").doesNotContain("fieldC");
    }

    @Test
    void aFileDeletedByTheReleaseIsAChangeWithAnEmptyAfterSide(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        // A BAU route file (or a template) exists before the release.
        Files.writeString(dir.resolve("enquiry.xml"),
                "<routes><route id=\"R9.10_enquiry\"><from uri=\"direct:R9.10_enquiry\"/><to uri=\"bean:x\"/></route></routes>\n");
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");
        // The 19.18.0 release DELETES it — the old app that still calls R9.10 breaks.
        Files.delete(dir.resolve("enquiry.xml"));
        commit(dir, "[JIRA-2][SG][19.18.0] delete the BAU route file");

        GitChangeService svc = new GitChangeService();
        GitChangeService.ReleaseChanges rc = svc.changedFor(dir, "19.18.0");
        // A deletion is a change, and it is reported as a deleted file.
        assertThat(rc.changedFiles()).contains("enquiry.xml");
        assertThat(rc.deletedFiles()).contains("enquiry.xml");
        // before = the pre-release content (route present); after = the deletion commit → file gone → null.
        List<String> before = svc.fileAtRef(dir, rc.beforeRefFor("enquiry.xml"), "enquiry.xml");
        List<String> after = svc.fileAtRef(dir, rc.afterRefFor("enquiry.xml"), "enquiry.xml");
        assertThat(before).isNotNull();
        assertThat(String.join("\n", before)).contains("R9.10_enquiry");
        assertThat(after).isNull();   // gone at the release's newest touch → the removal pass sees zero routes after
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
