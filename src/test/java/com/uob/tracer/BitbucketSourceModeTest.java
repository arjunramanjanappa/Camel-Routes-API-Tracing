package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
import com.uob.tracer.service.SourceResolver;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bitbucket-branch source mode must run the same analysis as a local path: resolveRoot clones
 * the repo at the branch and the scan runs on that checkout. Here the sample framework is committed
 * to a local git repo and analysed both ways — the results must match.
 */
class BitbucketSourceModeTest {

    @Test
    void versionDiffFromAClonedBranchMatchesTheLocalPath(@TempDir Path tmp) throws Exception {
        Path fixture = Path.of("src/test/resources/sample-framework");
        Path remote = tmp.resolve("remote");
        copyTree(fixture, remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("framework").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
        }

        RouteTraceService svc = new RouteTraceService("", new SourceResolver("", tmp.resolve("work").toString()));

        VersionDiffReport local = svc.versionDiff(new TraceRequest(null, "9.4", null, fixture.toString()));
        VersionDiffReport cloned = svc.versionDiff(
                new TraceRequest(null, "9.4", null, null, null, remote.toUri().toString(), "main"));

        assertThat(cloned.getChangedCount()).isEqualTo(local.getChangedCount());
        assertThat(cloned.getNewCount()).isEqualTo(local.getNewCount());
        assertThat(cloned.getUnchangedCount()).isEqualTo(local.getUnchangedCount());
        assertThat(cloned.getApis()).extracting(ApiDiff::operation).contains("fundTransferSubmitV2Api");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }
}
