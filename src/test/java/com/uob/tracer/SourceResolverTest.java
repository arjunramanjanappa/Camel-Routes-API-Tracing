package com.uob.tracer;

import com.uob.tracer.service.SourceResolver;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bitbucket-branch source mode clones a repo and checks out the requested branch/tag via
 * JGit. Validated against a local git repo (file:// clone) — no token or network needed; the
 * token only adds a Bearer header over HTTPS, which this path doesn't exercise.
 */
class SourceResolverTest {

    @Test
    void clonesAndChecksOutTheRequestedBranch(@TempDir Path tmp) throws Exception {
        Path remote = tmp.resolve("remote");
        Files.createDirectories(remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("common.txt"), "shared");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(remote.resolve("feature.txt"), "feature only");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
            git.checkout().setName("main").call();
        }
        String url = remote.toUri().toString();
        SourceResolver resolver = new SourceResolver("", tmp.resolve("work").toString());

        Path onMain = resolver.resolve(url, "main");
        assertThat(onMain.resolve("common.txt")).exists();
        assertThat(onMain.resolve("feature.txt")).doesNotExist();

        Path onFeature = resolver.resolve(url, "feature");   // different cache dir, branch resolved + checked out
        assertThat(onFeature.resolve("common.txt")).exists();
        assertThat(onFeature.resolve("feature.txt")).exists();

        // Re-resolving is idempotent (returns the same checkout).
        assertThat(resolver.resolve(url, "feature")).isEqualTo(onFeature);
    }

    @Test
    void reResolvingAnUnchangedBranchDoesNotRewriteTheWorkingTree(@TempDir Path tmp) throws Exception {
        // A fetch that brings no new commits must NOT re-checkout (which would bump file mtimes and
        // force the scan to rebuild every request) — the working tree, and its mtimes, stay stable.
        Path remote = tmp.resolve("remote");
        Files.createDirectories(remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("common.txt"), "shared");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
        }
        String url = remote.toUri().toString();
        SourceResolver resolver = new SourceResolver("", tmp.resolve("work").toString());

        Path dir = resolver.resolve(url, "main");
        Path file = dir.resolve("common.txt");
        long mtime1 = Files.getLastModifiedTime(file).toMillis();

        Thread.sleep(2100);   // past the 2s fetch throttle, so the next resolve really fetches
        Path dir2 = resolver.resolve(url, "main");   // fetches, but the branch didn't move → no re-checkout

        assertThat(dir2).isEqualTo(dir);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(mtime1);
    }

    @Test
    void aMissingBranchGivesAClearError(@TempDir Path tmp) throws Exception {
        Path remote = tmp.resolve("remote");
        Files.createDirectories(remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("f.txt"), "x");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
        }
        SourceResolver resolver = new SourceResolver("", tmp.resolve("work").toString());
        try {
            resolver.resolve(remote.toUri().toString(), "does-not-exist");
            org.junit.jupiter.api.Assertions.fail("expected an error for a missing branch");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("does-not-exist");
        }
    }
}
