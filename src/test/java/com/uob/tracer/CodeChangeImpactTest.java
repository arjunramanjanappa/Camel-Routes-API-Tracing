package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
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
 * The Release-Impact code-change feature: when an app/commit version (e.g. 19.18.0) is supplied, the diff
 * flags APIs whose Java {@code @Component} bean or route XML the release changed — including the shared-code
 * case where a bean used by an older release's route changes, so that older route must be re-tested too.
 */
class CodeChangeImpactTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.18_getResidenceRoute">
                  <from uri="direct:R9.18_getResidence"/>
                  <to uri="bean:residenceProcessor"/>
                  <to uri="direct:R7.14_getStatus"/>
                </route>
                <route id="R9.14_getResidenceRoute">
                  <from uri="direct:R9.14_getResidence"/>
                  <to uri="bean:residenceProcessor"/>
                  <to uri="direct:R7.14_getStatus"/>
                </route>
                <route id="R7.14_getStatusRoute">
                  <from uri="direct:R7.14_getStatus"/>
                  <to uri="bean:statusProcessor"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private static final String CONTROLLER = """
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class Endpoints {
                @PostMapping("/getResidence") public Object getResidence(Object b){ return null; }
                @PostMapping("/getStatus") public Object getStatus(Object b){ return null; }
            }
            """;

    private static String beanClass(String simpleName, String beanName, int value) {
        return "import org.springframework.stereotype.Component;\n"
                + "@Component(\"" + beanName + "\")\n"
                + "public class " + simpleName + " {\n"
                + "    public int score() { return " + value + "; }\n"
                + "}\n";
    }

    private ApiDiff residenceDiff(VersionDiffReport report) {
        return report.getApis().stream()
                .filter(a -> a.targetRoute() != null && a.targetRoute().contains("getResidence"))
                .findFirst().orElseThrow();
    }

    @Test
    void flagsAChangedSharedBeanAndTheOlderRouteThatMustBeRetested(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        Files.writeString(dir.resolve("routes.xml"), ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), CONTROLLER);
        Files.writeString(dir.resolve("ResidenceProcessor.java"), beanClass("ResidenceProcessor", "residenceProcessor", 1));
        Files.writeString(dir.resolve("StatusProcessor.java"), beanClass("StatusProcessor", "statusProcessor", 1));
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 19.18 release changes only StatusProcessor — the bean the SHARED R7.14 route uses.
        Files.writeString(dir.resolve("StatusProcessor.java"), beanClass("StatusProcessor", "statusProcessor", 2));
        commit(dir, "[JIRA-2][SG][19.18.0] tweak status scoring");

        RouteTraceService service = new RouteTraceService(dir.toString());
        VersionDiffReport report = service.versionDiff(
                new TraceRequest(null, "9.18", null, dir.toString(), null, null, null, List.of(), null, "19.18.0"));

        assertThat(report.getAppVersion()).isEqualTo("19.18.0");
        assertThat(report.getMatchedCommits()).isEqualTo(1);

        ApiDiff residence = residenceDiff(report);
        assertThat(residence.codeChanged()).isTrue();
        assertThat(residence.changedClasses()).anyMatch(c -> c.contains("statusProcessor"));
        // The changed bean lives on the 7.14 route (a different release than the 9.18 target) → re-test it.
        assertThat(residence.crossVersionRoutes()).anyMatch(rte -> rte.contains("R7.14_getStatus"));
        assertThat(report.getCodeChangedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unchangedClassMeansNoCodeChange(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        Files.writeString(dir.resolve("routes.xml"), ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), CONTROLLER);
        Files.writeString(dir.resolve("ResidenceProcessor.java"), beanClass("ResidenceProcessor", "residenceProcessor", 1));
        Files.writeString(dir.resolve("StatusProcessor.java"), beanClass("StatusProcessor", "statusProcessor", 1));
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // A 19.18 commit that touches an unrelated file only.
        Files.writeString(dir.resolve("Notes.java"), "class Notes {}\n");
        commit(dir, "[JIRA-2][SG][19.18.0] add notes");

        RouteTraceService service = new RouteTraceService(dir.toString());
        VersionDiffReport report = service.versionDiff(
                new TraceRequest(null, "9.18", null, dir.toString(), null, null, null, List.of(), null, "19.18.0"));

        ApiDiff residence = residenceDiff(report);
        assertThat(residence.codeChanged()).isFalse();
        // Notes.java isn't wired to any route → it should surface for manual review.
        assertThat(report.getUnmappedChangedFiles()).anyMatch(f -> f.endsWith("Notes.java"));
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
