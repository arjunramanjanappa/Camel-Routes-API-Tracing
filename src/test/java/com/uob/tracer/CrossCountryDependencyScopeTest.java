package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.ApiImpact;
import com.uob.tracer.api.CatalogResponse;
import com.uob.tracer.api.ImpactIndex;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
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
 * Cross-country leakage guard. A dependency source carries another country's versioned route
 * ({@code security-th-v1.xml} with {@code R6.0_validate}). Dependency files are wholesale-included in every
 * country scope so a {@code direct:} host resolves without an import — but that must NOT make another
 * country's versioned API a version of THIS country's API. For an SG diff at 9.14, {@code /validate}'s
 * immediate-lower must not resolve to the TH dependency's {@code R6.0_validate}.
 */
class CrossCountryDependencyScopeTest {

    private static String routeCtx(String id, String routeId, String backend) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "<log message=\"x\"/></route></routeContext></beans:beans>";
    }

    /** Primary SG source: SG.xml wires its own security context (R9.14_validate) via routeContextRef. */
    private Path primary(Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgSecurityContext\"/></camelContext></beans>");
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/security-sg-v1.xml"),
                routeCtx("sgSecurityContext", "R9.14_validate", "{{baseUrl}}/sg/public/validate"));
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/validate") public Object validate(Object b){ return null; }
                }
                """);
        return dir;
    }

    /** Dependency source: TH's versioned security route — NOT referenced by SG.xml. */
    private Path dependency(Path dir) throws Exception {
        Files.writeString(dir.resolve("security-th-v1.xml"),
                routeCtx("thSecurityContext", "R6.0_validate", "{{baseUrl}}/th/public/validate"));
        return dir;
    }

    @Test
    void thDependencyVersionIsNotTheSgImmediateLower(@TempDir Path primaryDir, @TempDir Path depDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + dependency(depDir));

        VersionDiffReport report = service.versionDiff(
                new TraceRequest(null, "9.14", null, null, "SG", null, null, deps, null, null));

        ApiDiff validate = report.getApis().stream()
                .filter(a -> "validate".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("validate API not in the SG diff"));

        // The 9.14 route is SG's own; the TH dependency's R6.0 must not be treated as its predecessor.
        assertThat(validate.targetRoute()).isEqualTo("R9.14_validate");
        assertThat(validate.lowerRoute()).isNotEqualTo("R6.0_validate");
        assertThat(validate.lowerVersion()).isNotEqualTo("6.0");
        // With no in-scope lower version, it's a NEW API for SG (nothing below 9.14 in SG's own scope).
        assertThat(validate.status()).isEqualTo(ApiDiff.NEW);
    }

    // ---------- Release Scope (catalog) + Release Test (impact-index) must not surface the TH dependency version ----------

    @Test
    void sgCatalogDoesNotSurfaceTheThDependencyVersion(@TempDir Path primaryDir, @TempDir Path depDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + dependency(depDir));

        CatalogResponse cat = (CatalogResponse) service.analyze(
                new TraceRequest(null, null, null, null, "SG", null, null, deps, null, null));

        assertThat(cat.getVersionsFound()).doesNotContain("6.0");
        List<String> routes = cat.getGroups().stream()
                .flatMap(g -> g.traces().stream())
                .map(TraceResponse::getResolvedRoute)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(routes).noneMatch(r -> r.contains("R6.0"));
    }

    @Test
    void sgImpactIndexResolvesToItsOwnRouteNotTheThDependency(@TempDir Path primaryDir, @TempDir Path depDir) throws Exception {
        RouteTraceService service = new RouteTraceService(primary(primaryDir).toString());
        List<String> deps = List.of("local:" + dependency(depDir));

        ImpactIndex idx = service.impactIndex(
                new TraceRequest(null, "9.14", null, null, "SG", null, null, deps, null, null));

        ApiImpact validate = idx.getApis().stream()
                .filter(a -> "validate".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("validate not in the SG impact-index"));
        assertThat(validate.resolvedRoute()).isEqualTo("R9.14_validate");
        assertThat(validate.routes()).noneMatch(r -> r.contains("R6.0"));
    }

    // ---------- same-source cross-country: another country's same-named route file must not leak via a loose import ----------

    /** A country bootstrap that pulls its routes via {@code <import resource="classpath:RES">}. */
    private static String importingBootstrap(String resource) {
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                + "<import resource=\"classpath:" + resource + "\"/>"
                + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\"/></beans>";
    }

    @Test
    void anotherCountrysSameNamedRouteFileIsNotPulledIntoThisCountrysClosure(@TempDir Path primaryDir) throws Exception {
        // SG.xml imports the UNQUALIFIED resource authCode.xml — at runtime the sg classpath entry wins.
        // Each country ships its own authCode.xml under its own dir; only SG's carries the new 9.14 route,
        // MY's carries an older R6.0 route. MY's file is NOT a dependency and NOT imported by SG.
        Files.writeString(primaryDir.resolve("SG.xml"), importingBootstrap("authCode.xml"));
        Files.createDirectories(primaryDir.resolve("sg"));
        Files.writeString(primaryDir.resolve("sg/authCode.xml"),
                routeCtx("sgAuthContext", "R9.14_authCode", "{{baseUrl}}/sg/auth/code/validate"));
        // MY is its own country bootstrap (also imports the unqualified authCode.xml) and ships the older
        // R6.0 route under its own dir — the very file that must NOT bleed into the SG scope.
        Files.writeString(primaryDir.resolve("MY.xml"), importingBootstrap("authCode.xml"));
        Files.createDirectories(primaryDir.resolve("my"));
        Files.writeString(primaryDir.resolve("my/authCode.xml"),
                routeCtx("myAuthContext", "R6.0_authCode", "{{baseUrl}}/my/auth/code/validate"));
        Files.writeString(primaryDir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/authCode") public Object authCode(Object b){ return null; }
                }
                """);

        VersionDiffReport report = new RouteTraceService(primaryDir.toString()).versionDiff(
                new TraceRequest(null, "9.14", null, null, "SG", null, null, null, null, null));

        ApiDiff authCode = report.getApis().stream()
                .filter(a -> "authCode".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("authCode API not in the SG diff"));

        // SG's own scope has only R9.14_authCode; MY's R6.0 must not become its predecessor.
        assertThat(authCode.targetRoute()).isEqualTo("R9.14_authCode");
        assertThat(authCode.lowerRoute()).isNotEqualTo("R6.0_authCode");
        assertThat(authCode.lowerVersion()).isNotEqualTo("6.0");
        assertThat(authCode.status()).isEqualTo(ApiDiff.NEW);
    }

    // ---------- code-change scoping: a class changed only in another country's routes must not flag here ----------

    private static String routeCtxBean(String id, String routeId, String bean) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"" + id + "\"><route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/><to uri=\"bean:" + bean + "\"/></route></routeContext></beans:beans>";
    }

    private static String beanClass(String simpleName, String beanName, int value) {
        return "import org.springframework.stereotype.Component;\n@Component(\"" + beanName + "\")\n"
                + "public class " + simpleName + " { public int score() { return " + value + "; } }\n";
    }

    @Test
    void sharedClassChangedOnlyInAnotherCountrysDependencyRouteIsNotFlaggedHere(@TempDir Path primaryDir,
                                                                                @TempDir Path depDir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");

        // Primary SG: a NEW R9.14_validate that uses bean sharedProc. No lower version in SG's own scope.
        Files.writeString(primaryDir.resolve("SG.xml"),
                "<beans xmlns=\"http://www.springframework.org/schema/beans\">"
                        + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                        + "<routeContextRef ref=\"sgSecurityContext\"/></camelContext></beans>");
        Files.createDirectories(primaryDir.resolve("sg"));
        Files.writeString(primaryDir.resolve("sg/security-sg-v1.xml"),
                routeCtxBean("sgSecurityContext", "R9.14_validate", "sharedProc"));
        Files.writeString(primaryDir.resolve("SharedProc.java"), beanClass("SharedProc", "sharedProc", 1));
        Files.writeString(primaryDir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints { @CommandHandler @PostMapping("/validate") public Object validate(Object b){ return null; } }
                """);
        initRepo(primaryDir);
        // The 19.14.0 release changes sharedProc.
        Files.writeString(primaryDir.resolve("SharedProc.java"), beanClass("SharedProc", "sharedProc", 2));
        commit(primaryDir, "[JIRA-1][SG][19.14.0] tweak sharedProc");

        // Dependency: TH's R6.0_validate uses the SAME bean — the ONLY other user of sharedProc.
        Files.writeString(depDir.resolve("security-th-v1.xml"),
                routeCtxBean("thSecurityContext", "R6.0_validate", "sharedProc"));
        List<String> deps = List.of("local:" + depDir);

        VersionDiffReport report = new RouteTraceService(primaryDir.toString()).versionDiff(
                new TraceRequest(null, "9.14", null, primaryDir.toString(), "SG", null, null, deps, null, "19.14.0"));

        ApiDiff validate = report.getApis().stream()
                .filter(a -> "validate".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("validate API not in the SG diff"));

        // In SG's own scope sharedProc is used ONLY by the new R9.14 route, so it's new code — not a shared
        // BAU change. The TH dependency's R6.0 usage must not make it pre-existing, nor be an impacted route.
        assertThat(validate.codeChanged()).isFalse();
        assertThat(validate.impactedRoutes()).noneMatch(r -> r.route() != null && r.route().contains("R6.0"));
        assertThat(validate.status()).isEqualTo(ApiDiff.NEW);
    }

    // ---------- git helpers ----------

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
        git(dir, "add", "-A");
        git(dir, "commit", "-m", "[JIRA-0][SG][19.10.0] baseline");
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
