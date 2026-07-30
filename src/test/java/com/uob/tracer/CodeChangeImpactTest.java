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
 * The Release-Impact code-change feature. "Code change" = a modified pre-existing (BAU) {@code @Component}
 * Java class (bean) a route uses — Java only; route XML / payload changes are covered by the version + payload
 * diffs. A NEW API that changes shared BAU code is promoted into the Changed group (BAU APIs using that class
 * need testing); an Unchanged API never shows a code change; a bean shipped only with the release's own new
 * route is new code and is not flagged.
 */
class CodeChangeImpactTest {

    // getResidence is NEW at 9.18 (only an R9.18 route). Its flow calls the shared BAU route R7.14_getStatusRoute,
    // which uses statusProcessor. residenceProcessor is used only by the new 9.18 route.
    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.18_getResidenceRoute">
                  <from uri="direct:R9.18_getResidence"/>
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

    private static void writeBaseline(Path dir) throws Exception {
        Files.writeString(dir.resolve("routes.xml"), ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), CONTROLLER);
        Files.writeString(dir.resolve("ResidenceProcessor.java"), beanClass("ResidenceProcessor", "residenceProcessor", 1));
        Files.writeString(dir.resolve("StatusProcessor.java"), beanClass("StatusProcessor", "statusProcessor", 1));
    }

    private static VersionDiffReport run918(Path dir) {
        return new RouteTraceService(dir.toString()).versionDiff(
                new TraceRequest(null, "9.18", null, dir.toString(), null, null, null, List.of(), null, "19.18.0"));
    }

    private ApiDiff apiByRoute(VersionDiffReport report, String routeFragment) {
        return report.getApis().stream()
                .filter(a -> a.targetRoute() != null && a.targetRoute().contains(routeFragment))
                .findFirst().orElseThrow();
    }

    @Test
    void newAndBauApisThatChangeSharedCodeAreBothPromotedToChanged(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release changes statusProcessor — a BAU class the shared R7.14 route uses.
        Files.writeString(dir.resolve("StatusProcessor.java"), beanClass("StatusProcessor", "statusProcessor", 2));
        commit(dir, "[JIRA-2][SG][19.18.0] tweak status scoring");

        VersionDiffReport report = run918(dir);
        assertThat(report.getAppVersion()).isEqualTo("19.18.0");
        assertThat(report.getMatchedCommits()).isEqualTo(1);

        // getResidence is a NEW API but it changes shared BAU code → flagged and promoted into Changed.
        ApiDiff residence = apiByRoute(report, "getResidence");
        assertThat(residence.status()).isEqualTo(ApiDiff.NEW);          // still rendered as "new"
        assertThat(residence.codeChanged()).isTrue();
        assertThat(residence.changedClasses()).anyMatch(c -> c.contains("statusProcessor"));
        assertThat(residence.changedClasses()).anyMatch(c -> c.contains("Test"));   // commit author shown
        assertThat(residence.changedClasses()).anyMatch(c -> c.contains("· 19.18.0"));   // per-version tag on the class
        assertThat(residence.changedVersions()).containsExactly("19.18.0");             // per-API version(s)
        // The re-test route carries the owning API path (no manual back-trace needed).
        assertThat(residence.impactedRoutes())
                .anyMatch(r -> r.route().contains("R7.14_getStatus") && r.category().equals("BAU")
                        && "/getStatus".equals(r.api()));
        assertThat(report.getChangedCount()).isEqualTo(2);   // getResidence (New→Changed) + getStatus (BAU class changed → Changed)
        assertThat(report.getNewCount()).isZero();
        // A shared-class code change is High test-priority and forces a backward-compat (older-version) re-test.
        assertThat(residence.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        assertThat(report.getHighRiskCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);

        // getStatus resolves to R7.14, whose statusProcessor the release changed — its OWN flow is impacted, so it
        // becomes a code change needing backward-compat and is promoted into Changed. Its status field stays
        // UNCHANGED (no route/flow change of its own), like a New API keeps "new" while landing under Changed.
        ApiDiff status = apiByRoute(report, "getStatus");
        assertThat(status.status()).isEqualTo(ApiDiff.UNCHANGED);
        assertThat(status.codeChanged()).isTrue();
        assertThat(status.changedClasses()).anyMatch(c -> c.contains("statusProcessor"));
    }

    @Test
    void aSharedBauClassDeletedByTheReleaseIsFlaggedAsRemovedHighRiskAndNeedsBackwardCompat(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release DELETES statusProcessor — a shared BAU class the R7.14 route still references. The
        // older flow breaks: this is a removed shared class → HIGH risk + backward compatibility required.
        Files.delete(dir.resolve("StatusProcessor.java"));
        commit(dir, "[JIRA-2][SG][19.18.0] remove statusProcessor");

        VersionDiffReport report = run918(dir);
        assertThat(report.getMatchedCommits()).isEqualTo(1);

        ApiDiff status = apiByRoute(report, "getStatus");
        assertThat(status.codeChanged()).isTrue();
        assertThat(status.changedClasses()).anyMatch(c -> c.contains("statusProcessor") && c.contains("REMOVED"));
        assertThat(status.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        // Re-test still points at the BAU route that used the now-removed class.
        assertThat(status.impactedRoutes()).anyMatch(r -> r.route().contains("R7.14_getStatus"));
        assertThat(report.getHighRiskCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aStepRemovedFromABauRouteInTheReleaseIsHighRiskAndNeedsBackwardCompat(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release edits the BAU route R7.14_getStatusRoute the old app still runs — dropping a step
        // (its statusProcessor call). Removing a step from a BAU route is backward-incompatible: High + BC.
        Files.writeString(dir.resolve("routes.xml"),
                ROUTES.replace("<to uri=\"bean:statusProcessor\"/>", ""));
        commit(dir, "[JIRA-2][SG][19.18.0] drop statusProcessor step from R7.14 BAU route");

        VersionDiffReport report = run918(dir);
        assertThat(report.getMatchedCommits()).isEqualTo(1);

        ApiDiff status = apiByRoute(report, "getStatus");
        assertThat(status.bauRouteEdits()).anyMatch(e -> e.route().contains("R7.14_getStatus")
                && e.removedSteps().stream().anyMatch(s -> s.contains("bean:statusProcessor")));
        assertThat(status.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        assertThat(report.getHighRiskCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aStepAddedToABauRouteInTheReleaseIsHighRiskBecauseItChangesProd(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release ADDS a step to the BAU route R7.14 the old app still runs. Editing a route already in
        // production changes existing PROD behaviour → High + backward-compat (even though it's additive).
        Files.writeString(dir.resolve("routes.xml"),
                ROUTES.replace("<to uri=\"bean:statusProcessor\"/>",
                        "<to uri=\"bean:statusProcessor\"/><to uri=\"bean:auditProcessor\"/>"));
        commit(dir, "[JIRA-2][SG][19.18.0] add auditProcessor step to R7.14 BAU route");

        VersionDiffReport report = run918(dir);

        ApiDiff status = apiByRoute(report, "getStatus");
        assertThat(status.bauRouteEdits()).anyMatch(e -> e.route().contains("R7.14_getStatus")
                && e.removedSteps().isEmpty()
                && e.addedSteps().stream().anyMatch(s -> s.contains("bean:auditProcessor")));
        assertThat(status.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        assertThat(status.codeChanged()).isFalse();     // it's a BAU-route edit, not a shared-class change
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aPayloadKeyRemovedFromABauRouteTemplateIsHighRiskAndNeedsBackwardCompat(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        Files.writeString(dir.resolve("routes.xml"), """
                <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
                  <routeContext id="c">
                    <route id="R9.8_payRoute"><from uri="direct:R9.8_pay"/><to uri="freemarker:templates/pay.ftl"/></route>
                  </routeContext>
                </beans:beans>
                """);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @PostMapping("/pay") public Object pay(Object b){ return null; }
                }
                """);
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/pay.ftl"),
                "{\n  \"serviceVersionNumber\": \"2.0\",\n  \"fieldA\": \"x\",\n  \"fieldB\": \"y\"\n}\n");
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release edits the payload TEMPLATE the BAU route R9.8_pay sends — dropping fieldB. A removed
        // payload key on a route the old app still runs is backward-incompatible: High + BC.
        Files.writeString(dir.resolve("templates/pay.ftl"),
                "{\n  \"serviceVersionNumber\": \"2.0\",\n  \"fieldA\": \"x\"\n}\n");
        commit(dir, "[JIRA-2][SG][19.18.0] drop fieldB from pay payload template");

        VersionDiffReport report = new RouteTraceService(dir.toString()).versionDiff(
                new TraceRequest(null, "9.18", null, dir.toString(), null, null, null, List.of(), null, "19.18.0"));

        ApiDiff pay = apiByRoute(report, "pay");
        assertThat(pay.bauRouteEdits()).anyMatch(e -> e.route().contains("R9.8_pay")
                && e.removedKeys().contains("fieldB"));
        assertThat(pay.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);
    }

    // A BAU route (/pay, R9.8) that sends a freemarker payload template — for the in-place payload value diff.
    private static void writePayFixture(Path dir, String template) throws Exception {
        Files.writeString(dir.resolve("routes.xml"), """
                <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
                  <routeContext id="c">
                    <route id="R9.8_payRoute"><from uri="direct:R9.8_pay"/><to uri="freemarker:templates/pay.ftl"/></route>
                  </routeContext>
                </beans:beans>
                """);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @PostMapping("/pay") public Object pay(Object b){ return null; }
                }
                """);
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/pay.ftl"), template);
    }

    private VersionDiffReport runPay918(Path dir) {
        return new RouteTraceService(dir.toString()).versionDiff(
                new TraceRequest(null, "9.18", null, dir.toString(), null, null, null, List.of(), null, "19.18.0"));
    }

    @Test
    void aPayloadValueChangedInABauRouteTemplateIsHighRiskAndNeedsBackwardCompat(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writePayFixture(dir, "{\n  \"serviceVersionNumber\": \"2.0\",\n  \"channel\": \"MOBILE\",\n  \"amount\": \"${ctx.amount}\"\n}\n");
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release changes a payload VALUE (channel MOBILE -> APP) the BAU route R9.8_pay sends — same
        // key, new value, so the old app now sends different data → High + BC. (amount's value is unchanged.)
        Files.writeString(dir.resolve("templates/pay.ftl"),
                "{\n  \"serviceVersionNumber\": \"2.0\",\n  \"channel\": \"APP\",\n  \"amount\": \"${ctx.amount}\"\n}\n");
        commit(dir, "[JIRA-2][SG][19.18.0] change channel value in pay template");

        VersionDiffReport report = runPay918(dir);

        ApiDiff pay = apiByRoute(report, "pay");
        assertThat(pay.bauRouteEdits()).anyMatch(e -> e.route().contains("R9.8_pay")
                && e.changedValues().stream().anyMatch(v -> v.key().equals("channel")
                        && v.before().equals("MOBILE") && v.after().equals("APP")));
        // Only channel changed — amount's value (${ctx.amount}) is identical on both sides.
        assertThat(pay.bauRouteEdits()).allSatisfy(e ->
                assertThat(e.changedValues()).noneMatch(v -> v.key().equals("amount")));
        assertThat(pay.risk()).isEqualTo(ApiDiff.RISK_HIGH);
        assertThat(report.getBackwardCompatCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aWhitespaceOnlyReformatOfABauRouteTemplateIsNotFlagged(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writePayFixture(dir, "{\n  \"serviceVersionNumber\": \"2.0\",\n  \"channel\": \"MOBILE\"\n}\n");
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // The 9.18 release only REINDENTS the template — same keys, same values. Whitespace normalisation means
        // this is not a payload change, so the BAU route is not flagged (no false positive on a cosmetic edit).
        Files.writeString(dir.resolve("templates/pay.ftl"),
                "{\n\n      \"serviceVersionNumber\":     \"2.0\",\n      \"channel\":      \"MOBILE\"\n\n}\n");
        commit(dir, "[JIRA-2][SG][19.18.0] reformat pay template");

        VersionDiffReport report = runPay918(dir);

        ApiDiff pay = apiByRoute(report, "pay");
        assertThat(pay.bauRouteEdits()).isEmpty();
        assertThat(pay.risk()).isEqualTo(ApiDiff.RISK_LOW);
    }

    @Test
    void doesNotFlagANewBeanShippedOnlyWithTheReleasesOwnRoute(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // residenceProcessor is used ONLY by the new 9.18 route — new code for the new route, not shared BAU.
        Files.writeString(dir.resolve("ResidenceProcessor.java"), beanClass("ResidenceProcessor", "residenceProcessor", 2));
        commit(dir, "[JIRA-2][SG][19.18.0] tweak residence scoring");

        VersionDiffReport report = run918(dir);
        ApiDiff residence = apiByRoute(report, "getResidence");
        assertThat(residence.codeChanged()).isFalse();
        assertThat(residence.status()).isEqualTo(ApiDiff.NEW);
        assertThat(report.getNewCount()).isEqualTo(1);   // stays New, not promoted
        assertThat(report.getCodeChangedCount()).isZero();
    }

    @Test
    void routeXmlOnlyChangeIsNotACodeChange(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // Change only route XML (add a log step to the shared route) — no Java class touched. Code-change is
        // Java-only, so nothing is flagged as a code change (route changes are the version diff's job).
        Files.writeString(dir.resolve("routes.xml"), ROUTES.replace(
                "<to uri=\"bean:statusProcessor\"/>",
                "<to uri=\"bean:statusProcessor\"/>\n      <log message=\"done\"/>"));
        commit(dir, "[JIRA-2][SG][19.18.0] add a log step");

        VersionDiffReport report = run918(dir);
        assertThat(report.getApis()).allMatch(a -> !a.codeChanged());
        assertThat(report.getCodeChangedCount()).isZero();
    }

    @Test
    void aChangedNonBeanJavaFileFlagsNothing(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        writeBaseline(dir);
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.14.0] baseline");

        // A changed .java that isn't a bean wired to any traced route → nothing to flag (we trace by flow).
        Files.writeString(dir.resolve("Notes.java"), "class Notes {}\n");
        commit(dir, "[JIRA-2][SG][19.18.0] add notes");

        VersionDiffReport report = run918(dir);
        assertThat(report.getApis()).allMatch(a -> !a.codeChanged());
        assertThat(report.getCodeChangedCount()).isZero();
    }

    // A shared bean (commonProcessor) used across many versions/families, plus a NEW getSummary API that also
    // uses it — for the "collapse to immediate-lower + future per family" rule.
    private static final String COLLAPSE_ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.10_getSummaryRoute"><from uri="direct:R9.10_getSummary"/><to uri="bean:commonProcessor"/></route>
                <route id="R9.4_getStatusRoute"><from uri="direct:R9.4_getStatus"/><to uri="bean:commonProcessor"/></route>
                <route id="R9.8_getStatusRoute"><from uri="direct:R9.8_getStatus"/><to uri="bean:commonProcessor"/></route>
                <route id="R9.10_getStatusRoute"><from uri="direct:R9.10_getStatus"/><to uri="bean:commonProcessor"/></route>
                <route id="R9.18_getStatusRoute"><from uri="direct:R9.18_getStatus"/><to uri="bean:commonProcessor"/></route>
                <route id="R9.8_getAppTypeRoute"><from uri="direct:R9.8_getAppType"/><to uri="bean:commonProcessor"/></route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void collapsesImpactedRoutesToImmediateLowerPlusFuturePerFamily(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        Files.writeString(dir.resolve("routes.xml"), COLLAPSE_ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @PostMapping("/getSummary") public Object getSummary(Object b){ return null; }
                }
                """);
        Files.writeString(dir.resolve("CommonProcessor.java"), beanClass("CommonProcessor", "commonProcessor", 1));
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.4.0] baseline");

        // The 9.10 release changes commonProcessor, used across getStatus (9.4/9.8/9.10/9.18) and getAppType (9.8).
        Files.writeString(dir.resolve("CommonProcessor.java"), beanClass("CommonProcessor", "commonProcessor", 2));
        commit(dir, "[JIRA-2][SG][19.10.0] tweak common processor");

        VersionDiffReport report = new RouteTraceService(dir.toString()).versionDiff(
                new TraceRequest(null, "9.10", null, dir.toString(), null, null, null, List.of(), null, "19.10.0"));

        ApiDiff summary = apiByRoute(report, "getSummary");
        assertThat(summary.codeChanged()).isTrue();
        var reTest = summary.impactedRoutes();
        // getStatus family: immediate-lower below 9.10 is 9.8 (BAU, not the superseded 9.4), the future 9.18,
        // and the release version 9.10 itself (Current — the change is part of this release too).
        assertThat(reTest).anyMatch(r -> r.route().contains("R9.8_getStatus") && r.category().equals("BAU"));
        assertThat(reTest).anyMatch(r -> r.route().contains("R9.18_getStatus") && r.category().equals("Future"));
        assertThat(reTest).anyMatch(r -> r.route().contains("R9.10_getStatus") && r.category().equals("Current"));
        // getAppType: only 9.8 exists → BAU.
        assertThat(reTest).anyMatch(r -> r.route().contains("R9.8_getAppType") && r.category().equals("BAU"));
        // The superseded 9.4 (below the immediate-lower) is dropped.
        assertThat(reTest).noneMatch(r -> r.route().contains("R9.4_getStatus"));
        // getSummary is a declared controller endpoint, so its own (Current) route carries the API path.
        assertThat(reTest).anyMatch(r -> r.route().contains("R9.10_getSummary") && "/getSummary".equals(r.api()));
    }

    // An entry route (on a controller) that delegates to a helper route which holds the changed bean.
    private static final String DEPENDENT_ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.10_doStuffRoute"><from uri="direct:R9.10_doStuff"/><to uri="direct:R9.10_helper"/></route>
                <route id="R9.10_helperRoute"><from uri="direct:R9.10_helper"/><to uri="bean:helperProcessor"/></route>
                <route id="R9.8_getInfoRoute"><from uri="direct:R9.8_getInfo"/><to uri="direct:R9.8_helper"/></route>
                <route id="R9.8_helperRoute"><from uri="direct:R9.8_helper"/><to uri="bean:helperProcessor"/></route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void tracesAnIndirectlyCalledRouteBackToItsEntryApiWithTheFullChain(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI not available");
        Files.writeString(dir.resolve("routes.xml"), DEPENDENT_ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @PostMapping("/doStuff") public Object doStuff(Object b){ return null; }
                    @PostMapping("/getInfo") public Object getInfo(Object b){ return null; }
                }
                """);
        Files.writeString(dir.resolve("HelperProcessor.java"), beanClass("HelperProcessor", "helperProcessor", 1));
        initRepo(dir);
        commit(dir, "[JIRA-1][SG][19.8.0] baseline");

        // The 9.10 release changes helperProcessor — held by helper routes that are NOT on any controller.
        Files.writeString(dir.resolve("HelperProcessor.java"), beanClass("HelperProcessor", "helperProcessor", 2));
        commit(dir, "[JIRA-2][SG][19.10.0] tweak helper");

        VersionDiffReport report = new RouteTraceService(dir.toString()).versionDiff(
                new TraceRequest(null, "9.10", null, dir.toString(), null, null, null, List.of(), null, "19.10.0"));

        ApiDiff doStuff = apiByRoute(report, "doStuff");
        assertThat(doStuff.codeChanged()).isTrue();
        var rt = doStuff.impactedRoutes();
        // The helper route was reached indirectly, but traces back to /doStuff with the full entry→helper chain.
        assertThat(rt).anyMatch(r -> "Current".equals(r.category()) && "/doStuff".equals(r.api())
                && r.routePath().equals(List.of("R9.10_doStuffRoute", "R9.10_helperRoute")));
        // The same class is held by getInfo's production (BAU) helper — traced back to /getInfo, not "unknown".
        assertThat(rt).anyMatch(r -> "BAU".equals(r.category()) && "/getInfo".equals(r.api())
                && r.routePath().equals(List.of("R9.8_getInfoRoute", "R9.8_helperRoute")));
        // No route is left unattributed.
        assertThat(rt).allMatch(r -> r.api() != null);
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
