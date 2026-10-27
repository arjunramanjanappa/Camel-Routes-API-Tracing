package io.traceguard.service;

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

/** git blame of a line range: the distinct commits behind it (hash, author, subject) and the derived author list. */
class GitBlameServiceTest {

    @Test
    void shouldReturnEachCommitBehindTheRangeWithItsAuthorAndSubject(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        initRepo(dir);
        Files.writeString(dir.resolve("route.xml"), "<route id=\"a\"/>\n");
        git(dir, "config", "user.name", "Alice");
        commit(dir, "[SGMB-1][SG][19.18.0] first route");
        Files.writeString(dir.resolve("route.xml"), "<route id=\"a\"/>\n<route id=\"b\"/>\n");
        git(dir, "config", "user.name", "Bob");
        commit(dir, "[SGMB-2][SG][19.18.0] second route");
        GitBlameService svc = new GitBlameService();

        List<GitBlameService.BlameCommit> commits = svc.commits(dir.resolve("route.xml"), 1, 2);

        assertThat(commits).hasSize(2);
        assertThat(commits).extracting(GitBlameService.BlameCommit::author).containsExactly("Alice", "Bob");
        assertThat(commits).extracting(GitBlameService.BlameCommit::summary)
                .containsExactly("[SGMB-1][SG][19.18.0] first route", "[SGMB-2][SG][19.18.0] second route");
        assertThat(commits).allMatch(c -> c.hash().matches("[0-9a-f]{40}"));
        assertThat(svc.authors(dir.resolve("route.xml"), 1, 2)).containsExactly("Alice", "Bob");
        assertThat(svc.statsAndReset().calls()).isEqualTo(2);
    }

    @Test
    void shouldReturnNothingForAnInvalidRangeOrAFileOutsideGit(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("plain.txt"), "x\n");
        GitBlameService svc = new GitBlameService();

        assertThat(svc.commits(dir.resolve("plain.txt"), 1, 1)).isEmpty();   // not a work tree
        assertThat(svc.commits(dir.resolve("plain.txt"), 2, 1)).isEmpty();   // end before start
        assertThat(svc.commits(null, 1, 1)).isEmpty();
        assertThat(svc.authors(dir.resolve("plain.txt"), 1, 1)).isEmpty();
    }

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
