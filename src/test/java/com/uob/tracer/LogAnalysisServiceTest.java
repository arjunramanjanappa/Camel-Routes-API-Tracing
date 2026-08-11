package com.uob.tracer;

import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.BackendCallResult;
import com.uob.tracer.api.BackendLogResult;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.api.ModuleLogReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.LogRulesService;
import com.uob.tracer.service.LogRulesService.AppRules;
import com.uob.tracer.service.LogRulesService.Rule;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Correlates synthetic output logs (src/test/resources/sample-logs) against the
 * traced footprint of the sample framework, covering every end-to-end verdict.
 */
class LogAnalysisServiceTest {

    private static final String FW = "src/test/resources/sample-framework";
    private static final String V2 = "/payment/v2/fund/submit";   // resolves to R9.4

    private final LogAnalysisService service = new LogAnalysisService(new RouteTraceService(FW));

    private LogAnalysisReport analyze(String logFile, String version) throws IOException {
        return analyze(logFile, version, "Mighty");
    }

    private LogAnalysisReport analyze(String logFile, String version, String app) throws IOException {
        // all=true ⇒ front-end report for the whole release (the API-centric tests).
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/sample-logs/" + logFile))) {
            return service.analyze(in, logFile, version, null, FW, null, null, true, app);
        }
    }

    private LogAnalysisReport analyzeBackends(String logFile, String version, List<String> backends) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/sample-logs/" + logFile))) {
            return service.analyze(in, logFile, version, null, FW, List.of(), backends, false, "Mighty");
        }
    }

    private ApiLogResult api(LogAnalysisReport r, String apiPath) {
        return r.apis().stream().filter(a -> a.api().equals(apiPath)).findFirst().orElseThrow();
    }

    @Test
    void aHostSkipRuleExcludesTheBackendFromTheVerdict(@TempDir Path home) throws IOException {
        // Configure a skip rule for the /bfs/ft/own/submit backend, then analyse the same e2e log: that backend
        // row must read SKIPPED (neither pass nor fail) instead of driving the API's verdict.
        LogRulesService rules = new LogRulesService(home.toString(), new ObjectMapper());
        rules.saveApp("Mighty", false, new AppRules(List.of(),
                List.of(new Rule("*/bfs/ft/own/submit", null, List.of(), true))));
        LogAnalysisService svc = new LogAnalysisService(new RouteTraceService(FW), rules);

        LogAnalysisReport rep;
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/sample-logs/analysis-e2e.log"))) {
            rep = svc.analyze(in, "analysis-e2e.log", "9.4", null, FW, null, null, true, "Mighty");
        }
        ApiLogResult v2 = api(rep, V2);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SKIPPED);   // skipped by config
            assertThat(b.failed()).isZero();                        // never counted as a failure
        });
    }

    @Test
    void aBackendCallAtADifferentServiceVersionIsNotAttributedToThisFlow() throws IOException {
        // Full logs (all releases) → own/submit expects svc 2.2 for this release, but the only call to it in the
        // log ran at 2.9 (another release's traffic). That call must NOT count as covering the 2.2 flow: the flow
        // reads NOT TESTED (not a misleading Success on a foreign-version call), so the API is PARTIAL.
        String fe = "/mty-banking-01/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        String corr = "c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9";
        String log =
            "2026-06-11 18.43.45.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][]-" + fe
                + " - Request - {\"serviceRequest\":{}}\n"
            + "2026-06-11 18.43.45.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][IOS][] -"
                + be + " - [Request] : {\"serviceVersionNumber\":\"2.9\"}\n"
            + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][IOS][230ms] -"
                + be + " - [Response] : {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
            + "2026-06-11 18.43.45.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][300ms]-" + fe
                + " - Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
        LogAnalysisReport r = analyzeText(log, "9.4");
        ApiLogResult v2 = api(r, V2);
        // The 2.9 call is not attributed to the 2.2 flow → that flow is Not Tested (never Success).
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.expectedServiceVersion()).isEqualTo("2.2");
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);
            assertThat(b.observedPath()).isNull();   // not shown as "seen" — the foreign-version call doesn't count
        });
    }

    @Test
    void aBackendFlowThatClearsItsPassRateDoesNotFailTheApiEvenIfTheLatestCallFailed() throws IOException {
        // own/submit exercised 21× at svc 2.2: 20 pass, the LATEST fails (≈95% pass, at/above the 95% bar). Like
        // the front end's pass-rate tolerance, the flow passes the verdict — so the API is Partial (the sibling
        // change flows were never tested), NOT Failed — even though the backend's own status pill still shows the
        // latest (failed) call.
        String fe = "/mty-banking-01/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        StringBuilder log = new StringBuilder();
        int minute = 10;
        for (int i = 0; i <= 20; i++) {
            String corr = String.format("%032d", i);
            String code = i == 20 ? "00911" : "0000000";   // the latest call (i=20) fails
            log.append("2026-06-11 18.").append(minute).append(".00.100 [t] INFO [MightyMessage][MTY][s][u][9.4][")
               .append(corr).append("][IOS][]-").append(fe).append(" - Request - {}\n");
            log.append("2026-06-11 18.").append(minute).append(".00.150 [t] INFO [MightyHostMessage][MTY][s][u][9.4][")
               .append(corr).append("][IOS][] -").append(be).append(" - [Request] : {\"serviceVersionNumber\":\"2.2\"}\n");
            log.append("2026-06-11 18.").append(minute).append(".00.250 [t] INFO [MightyHostMessage][MTY][s][u][9.4][")
               .append(corr).append("][IOS][60ms] -").append(be).append(" - [Response] : {\"serviceResponse\":{\"responseCode\":\"").append(code).append("\"}}\n");
            log.append("2026-06-11 18.").append(minute).append(".00.350 [t] INFO [MightyMessage][MTY][s][u][9.4][")
               .append(corr).append("][IOS][120ms]-").append(fe).append(" - Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n");
            minute++;
        }
        LogAnalysisReport r = analyzeText(log.toString(), "9.4");
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit clears the bar; siblings not tested
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.attempts()).isEqualTo(21);
            assertThat(b.passed()).isEqualTo(20);
            assertThat(b.status()).isEqualTo(LogStatus.FAILED);   // the pill still shows the latest (failed) call
        });
    }

    @Test
    void aVersionLessLatestCallDoesNotHideAServiceVersionLoggedByEarlierCalls() throws IOException {
        // own/submit expects 2.2. An earlier SUCCESS logged svc 2.2; the LATEST call is a failure whose error
        // payload omits serviceVersionNumber. The row's version must reflect the 2.2 that was seen (svcOk TRUE),
        // not read null off the latest call and drop to "expected but not seen".
        String fe = "/mty-banking-01/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        String a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String log =
            // txn A — success at svc 2.2
            "2026-06-11 18.43.45.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + a + "][IOS][]-" + fe + " - Request - {}\n"
            + "2026-06-11 18.43.45.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + a + "][IOS][] -" + be
                + " - [Request] : {\"serviceVersionNumber\":\"2.2\"}\n"
            + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + a + "][IOS][200ms] -" + be
                + " - [Response] : {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
            + "2026-06-11 18.43.45.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + a + "][IOS][300ms]-" + fe + " - Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
            // txn B — LATER, a failure whose payload has no serviceVersionNumber
            + "2026-06-11 18.43.46.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + b + "][IOS][]-" + fe + " - Request - {}\n"
            + "2026-06-11 18.43.46.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + b + "][IOS][] -" + be
                + " - [Request] : {}\n"
            + "2026-06-11 18.43.46.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + b + "][IOS][200ms] -" + be
                + " - [Response] : {\"serviceResponse\":{\"responseCode\":\"00911\"}}\n"
            + "2026-06-11 18.43.46.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + b + "][IOS][300ms]-" + fe + " - Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
        LogAnalysisReport r = analyzeText(log, "9.4");
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.backends()).anySatisfy(bk -> {
            assertThat(bk.backend()).contains("/bfs/ft/own/submit");
            assertThat(bk.loggedServiceVersion()).isEqualTo("2.2");   // the version an earlier call logged
            assertThat(bk.serviceVersionOk()).isTrue();               // 2.2 == expected 2.2 → verified ✓
        });
    }

    @Test
    void aRuleCanAssertTheExpectedServiceVersionWhenTheScanCantDeriveIt(@TempDir Path home) throws IOException {
        // A backend whose service version is set in Java → the route scan derives none. A rule asserts it
        // (svcVersion 9.9). That becomes the expected version: a call logged at 9.9 is attributed AND validated
        // (green ✓ / SUCCESS); the earlier test covers a mismatched version being left Not Tested.
        LogRulesService rules = new LogRulesService(home.toString(), new ObjectMapper());
        rules.saveApp("Mighty", false, new AppRules(List.of(),
                List.of(new LogRulesService.Rule("*/bfs/ft/own/submit", "", List.of(), false, "9.9"))));
        LogAnalysisService svc = new LogAnalysisService(new RouteTraceService(FW), rules);

        String fe = "/mty-banking-01/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        String corr = "d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9";
        String log =
            "2026-06-11 18.43.45.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][]-" + fe
                + " - Request - {\"serviceRequest\":{}}\n"
            + "2026-06-11 18.43.45.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][IOS][] -"
                + be + " - [Request] : {\"serviceVersionNumber\":\"9.9\"}\n"
            + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][IOS][230ms] -"
                + be + " - [Response] : {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
            + "2026-06-11 18.43.45.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][300ms]-" + fe
                + " - Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
        LogAnalysisReport r;
        try (InputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            r = svc.analyze(in, "rule-svc.log", "9.4", null, FW, null, null, true, "Mighty");
        }
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.expectedServiceVersion()).isEqualTo("9.9");   // from the rule, not the scan
            assertThat(b.loggedServiceVersion()).isEqualTo("9.9");
            assertThat(b.serviceVersionOk()).isTrue();                 // exact match → green
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void backendCodeFromACustomFieldPassesWhenARuleDeclaresIt(@TempDir Path home) throws IOException {
        // A BACKEND host whose response reports its outcome under a custom key (ResponseHeader.errorcode:"0000",
        // no responseCode). A rule matching that hosturl, field errorcode, success 0000, must read + pass it.
        LogRulesService rules = new LogRulesService(home.toString(), new ObjectMapper());
        rules.saveApp("Mighty", false, new AppRules(List.of(),
                List.of(new Rule("*/bfs/ft/own/submit", "errorcode", List.of("0000"), false))));
        LogAnalysisService svc = new LogAnalysisService(new RouteTraceService(FW), rules);

        String fe = "/mty-banking/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        String corr = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
        String log =
            "2026-06-11 18.43.45.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][]-" + fe
                + " -Request - {\"x\":1}\n"
            + "2026-06-11 18.43.45.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][Android][] -"
                + be + " - [Request] : {\"serviceVersionNumber\":\"2.2\"}\n"
            + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][Android][230ms] -"
                + be + " - [Response] : {\"ResponseHeader\":{\"errorcode\":\"0000\"}}\n"
            + "2026-06-11 18.43.45.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][300ms]-" + fe
                + " -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
        LogAnalysisReport r;
        try (InputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            r = svc.analyze(in, "custom-be.log", "9.4", null, FW, null, null, true, "Mighty");
        }
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);   // errorcode 0000 read + matched by the rule
        });
    }

    @Test
    void backendRuleFieldWinsOverAStrayResponseCodeInThePayload(@TempDir Path home) throws IOException {
        // The host's authoritative code is under the rule's field (errorcode:0000 = success), but the payload
        // ALSO carries a responseCode elsewhere (e.g. an echoed/nested non-zero). The matched rule's field must
        // win — else the generic responseCode shadows it and the row wrongly fails.
        LogRulesService rules = new LogRulesService(home.toString(), new ObjectMapper());
        rules.saveApp("Mighty", false, new AppRules(List.of(),
                List.of(new Rule("*/bfs/ft/own/submit", "errorcode", List.of("0000"), false))));
        LogAnalysisService svc = new LogAnalysisService(new RouteTraceService(FW), rules);

        String fe = "/mty-banking/services/sg/payment/v2/fund/submit";
        String be = "/mty-banking-01/bfs/ft/own/submit";
        String corr = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
        String beResp = "{\"requesterContext\":{\"responseCode\":\"99999\"},\"ResponseHeader\":{\"errorcode\":\"0000\"}}";
        String log =
            "2026-06-11 18.43.45.100 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][]-" + fe
                + " -Request - {\"x\":1}\n"
            + "2026-06-11 18.43.45.205 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][Android][] -"
                + be + " - [Request] : {\"serviceVersionNumber\":\"2.2\"}\n"
            + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.4][" + corr + "][Android][230ms] -"
                + be + " - [Response] : " + beResp + "\n"
            + "2026-06-11 18.43.45.400 [t] INFO [MightyMessage][MTY][s][u][9.4][" + corr + "][IOS][300ms]-" + fe
                + " -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
        LogAnalysisReport r;
        try (InputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            r = svc.analyze(in, "shadow-be.log", "9.4", null, FW, null, null, true, "Mighty");
        }
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);   // errorcode wins over the stray responseCode
        });
    }

    @Test
    void analyzeModulesParsesTheUploadOnceAcrossFlavours() throws IOException {
        // Two modules of DIFFERENT flavours (Mighty + SPL) go through analyzeModules, which parses the upload
        // ONCE and buckets records per flavour in a single read. The Mighty module must get exactly the same
        // per-API verdicts as the trusted single-flavour analyze; the SPL module (this Mighty log carries no
        // SPLMessage lines) sees no transactions — proving the single-pass per-flavour bucketing is correct.
        LogAnalysisReport baseline = analyze("analysis-e2e.log", "9.4");

        LogAnalysisService.LogSource src =
                () -> Files.newInputStream(Path.of("src/test/resources/sample-logs/analysis-e2e.log"));
        List<LogAnalysisService.ModuleSpec> specs = List.of(
                new LogAnalysisService.ModuleSpec("mighty", FW, null, null, "Mighty"),
                new LogAnalysisService.ModuleSpec("spl", FW, null, null, "SPL"));
        List<ModuleLogReport> reports = service.analyzeModules(src, "analysis-e2e.log", "9.4", null, specs, List.of());

        ModuleLogReport mighty = reports.stream().filter(m -> m.name().equals("mighty")).findFirst().orElseThrow();
        ModuleLogReport spl = reports.stream().filter(m -> m.name().equals("spl")).findFirst().orElseThrow();

        // Same APIs + verdicts as the single-flavour path — the shared single-pass parse changed nothing.
        assertThat(mighty.report().apis().stream().map(a -> a.api() + "=" + a.status()).sorted().toList())
                .isEqualTo(baseline.apis().stream().map(a -> a.api() + "=" + a.status()).sorted().toList());
        assertThat(mighty.report().transactions()).isEqualTo(baseline.transactions());
        // The SPL flavour matched none of this Mighty log's lines.
        assertThat(spl.report().transactions()).isZero();
    }

    @Test
    void successfulTransactionIsGreenAndPicksLatestAttempt() throws IOException {
        LogAnalysisReport r = analyze("analysis-mixed.log", "9.4");

        // Pre-filter ignored the no-marker line; 10 marker lines parsed into 3 txns.
        assertThat(r.matchedLines()).isEqualTo(10);
        assertThat(r.transactions()).isEqualTo(3);

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        // The own/submit flow succeeded, but the other release branches (intra / inter / fraud-check) were
        // never exercised — coverage is incomplete, so the API is PARTIAL regardless of the front-end pass
        // rate (the pass-rate threshold only decides once every flow is tested at least once). The latest run
        // (C1) supplies the headline latency / correlation id.
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(v2.correlationId()).isEqualTo("C1");
        assertThat(v2.feLatencyMs()).isEqualTo(500);
        // Only the two 9.4 attempts count; the 9.3 one (C3) is excluded.
        assertThat(v2.attempts()).isEqualTo(2);
        assertThat(v2.successCount()).isEqualTo(1);
        assertThat(v2.failureCount()).isEqualTo(1);
        // The own-account backend was observed and succeeded.
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            assertThat(b.latencyMs()).isEqualTo(230);
        });
    }

    @Test
    void failedAttemptsAreGroupedByResponseCodeForInvestigation() throws IOException {
        // Four 9.4 attempts for the same API: two fail 00911, one fails 00999, one succeeds.
        // The report groups the failures by responseCode, most-frequent first.
        String path = "/mty-banking/services/sg/payment/v2/fund/submit";
        String[][] attempts = { {"A1", "00911"}, {"A2", "00911"}, {"A3", "00999"}, {"A4", "0000000"} };
        StringBuilder log = new StringBuilder();
        int minute = 10;
        for (String[] a : attempts) {
            log.append("2026-06-11 18.").append(minute).append(".00.100 [t] INFO [MightyMessage][MTY][s][u][9.4][")
               .append(a[0]).append("][IOS][]-").append(path).append(" -Request - {\"x\":1}\n");
            log.append("2026-06-11 18.").append(minute).append(".00.400 [t] INFO [MightyMessage][MTY][s][u][9.4][")
               .append(a[0]).append("][IOS][300ms]-").append(path)
               .append(" -Response - {\"serviceResponse\":{\"responseCode\":\"").append(a[1]).append("\"}}\n");
            minute++;
        }

        LogAnalysisReport r = analyzeText(log.toString(), "9.4");
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.attempts()).isEqualTo(4);
        assertThat(v2.successCount()).isEqualTo(1);
        assertThat(v2.failureCount()).isEqualTo(3);
        // Grouped by code, ordered by count (00911 twice before 00999 once), summing to failureCount.
        assertThat(v2.failuresByCode()).containsExactly(entry("00911", 2), entry("00999", 1));
    }

    @Test
    void reportsTheLogTimeSpanFromEarliestToLatestTimestamp() throws IOException {
        // The report carries the window the log actually covers: the earliest and latest raw timestamps
        // (order-independent — not assuming the export was sorted) and the seconds between them.
        String path = "/mty-banking/services/sg/payment/v2/fund/submit";
        String log =
            "2026-06-11 18.13.00.400 [t] INFO [MightyMessage][MTY][s][u][9.4][A1][IOS][300ms]-" + path
                + " -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
            + "2026-06-11 18.10.00.100 [t] INFO [MightyMessage][MTY][s][u][9.4][A1][IOS][]-" + path
                + " -Request - {\"x\":1}\n";   // deliberately latest-first: min/max must not depend on order
        LogAnalysisReport r = analyzeText(log, "9.4");
        assertThat(r.logStart()).isEqualTo("2026-06-11 18.10.00.100");
        assertThat(r.logEnd()).isEqualTo("2026-06-11 18.13.00.400");
        assertThat(r.logSpanSeconds()).isEqualTo(180);   // 3 minutes
    }

    @Test
    void versionAndContextPathFoundByPatternNotPosition() throws IOException {
        // Real-world shape: a /mty-banking-01/ context prefix and extra bracket
        // fields push the version off its "expected" slot (here it sits at index 5,
        // after MTY/channel/session/user/device). Pattern matching must still find
        // 9.4 and match the API by path suffix — otherwise everything reads "not tested".
        LogAnalysisReport r = analyze("analysis-realistic.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.correlationId()).isEqualTo("C9");   // the field right after the version
        assertThat(v2.feLatencyMs()).isEqualTo(500);      // the 500ms-shaped field
        assertThat(v2.backends()).anySatisfy(b -> {       // the exercised flow parsed and passed
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void tolerantOfWhitespaceAroundSeparatorDashes() throws IOException {
        // Real-world spacing: "[] -/path - Request - {json}" — spaces around the dash
        // before the path and around the Request/Response separator — unlike the compact
        // "[]-/path -Request - {json}". Both shapes must parse, else every line is dropped
        // and the API reads "not tested".
        LogAnalysisReport r = analyze("analysis-spaced.log", "9.4");

        assertThat(r.matchedLines()).isEqualTo(3);
        assertThat(r.unparsedLines()).isZero();
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.correlationId()).isEqualTo("C9");
        assertThat(v2.feLatencyMs()).isEqualTo(500);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void endToEndMixedFormatsCorrelateEachApiWithItsOwnBackend() throws IOException {
        // One realistic log mixing every variant: a noise line, FE (MightyMessage, no
        // brackets, "-" json) and BE (MightyHostMessage, "[Request]"/"[Response]", ":" json,
        // one jwt-prefixed request, one plain). Two APIs with distinct correlation ids must
        // be told apart and each tied to its OWN backend end-to-end.
        LogAnalysisReport r = analyze("analysis-e2e.log", "9.4");

        assertThat(r.transactions()).isEqualTo(2);     // two correlation ids; the noise line ignored

        ApiLogResult fund = api(r, "/payment/v2/fund/submit");
        assertThat(fund.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit ok; other release branches not tested
        assertThat(fund.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            assertThat(b.loggedServiceVersion()).isEqualTo("2.2");
        });

        ApiLogResult limit = api(r, "/payment/v2/limit/initiate");
        assertThat(limit.status()).isEqualTo(LogStatus.FAILED);   // its change backend was exercised and failed
        // limit's traced api is /asv/transaction/limit/initiate, but the host LOGS its
        // hosturl /host-mng/limit/initiate — so the match must be via the hosturl while the
        // displayed backend stays the api value.
        BackendCallResult be = limit.backends().stream()
                .filter(b -> b.backend().contains("/asv/transaction/limit/initiate")).findFirst().orElseThrow();
        assertThat(be.status()).isEqualTo(LogStatus.FAILED);
        assertThat(be.responseCode()).isEqualTo("00911");
        assertThat(be.observedPath()).contains("/host-mng/limit/initiate");   // matched via hosturl, not the api
        // No cross-contamination: limit's own/submit-type backends are not its concern.
        assertThat(limit.backends()).noneSatisfy(b ->
                assertThat(b.observedPath()).contains("/bfs/ft/own/submit"));
    }

    @Test
    void realRemitFormatParsesAndBackendCorrelates() throws IOException {
        // Arjun's exact masked lines: colon time, UUID-with-dashes field, non-hex masked
        // correlation id, "[jwt]: true, -" before a dashed URL (/bfs-mng/...). All four
        // lines must parse and the backend (traced tail /payee/remit/initiate) must match
        // /bfs-mng/payee/remit/initiate by suffix.
        LogAnalysisReport r = analyzeBackends("analysis-remit.log", "9.18",
                List.of("{{dge.bfs.mng}}/payee/remit/initiate"));

        assertThat(r.matchedLines()).isEqualTo(4);     // all 4 lines parsed
        assertThat(r.unparsedLines()).isZero();
        BackendLogResult be = r.backends().get(0);
        assertThat(be.tested()).isTrue();
        assertThat(be.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(be.latencyMs()).isEqualTo(193);
        assertThat(be.loggedServiceVersion()).isEqualTo("2.0");
    }

    @Test
    void aNestedPlaceholderBackendStripsToItsPathTailAndCorrelates() throws IOException {
        // A NESTED Camel property placeholder with a default — {{key:{{default}}}} — must strip to its path
        // tail (/payee/remit/initiate), not leave a dangling "}}" (which would make the log-path match fail
        // and the backend show Not Tested). Same fixture as the single-placeholder case above.
        LogAnalysisReport r = analyzeBackends("analysis-remit.log", "9.18",
                List.of("{{am5.mock.url:{{am5.p.mfa.url}}}}/payee/remit/initiate"));

        BackendLogResult be = r.backends().get(0);
        assertThat(be.tested()).isTrue();                       // would be Not Tested if "}}" leaked into the tail
        assertThat(be.status()).isEqualTo(LogStatus.SUCCESS);
    }

    @Test
    void jwtHostRequestIsParsedAndColonSeparatedResponseMatches() throws IOException {
        // The real MightyHostMessage shapes: the Request carries the backend URL after a
        // "[jwt]: true,  -" prefix, and the JSON follows the direction with a ":" (not a
        // "-"). Both must parse so the backend is correlated end-to-end — earlier these
        // were dropped, which is why the backend always read "not tested".
        LogAnalysisReport r = analyze("analysis-jwt.log", "9.4");

        assertThat(r.matchedLines()).isEqualTo(4);     // FE req/resp + BE req(jwt)/resp(colon)
        assertThat(r.unparsedLines()).isZero();
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            assertThat(b.latencyMs()).isEqualTo(230);                 // from the colon-separated Response
            assertThat(b.loggedServiceVersion()).isEqualTo("2.2");    // from the [jwt] Request
        });
    }

    @Test
    void correlationIdMatchedByTraceIdShapeNotPosition() throws IOException {
        // The correlation id is a long-hex trace id. It's found by that shape (the only
        // hex field), and the front-end + host lines that share it pair into one txn.
        LogAnalysisReport r = analyze("analysis-traceid.log", "9.4");

        assertThat(r.transactions()).isEqualTo(1);
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.correlationId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void responseCodeParsedFromJsonAtAnyDepthAndEmptyVersionIsBase() throws IOException {
        // Base-release lines (EMPTY version bracket) whose responseCode is a NUMBER nested
        // at an arbitrary depth (and one with a different-cased key). The payload is parsed
        // as JSON and searched, so it works for any API shape; the empty version must read
        // as base (0.0) rather than dropping the line.
        LogAnalysisReport r = analyze("analysis-jsonbase.log", "");   // blank = no version scoping

        assertThat(r.unparsedLines()).isZero();                       // empty version didn't drop the lines
        assertThat(r.transactions()).isEqualTo(1);                    // all three share correlation id C9
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);         // numeric responseCode 0 → pass
        assertThat(v2.responseCode()).isEqualTo("0");
        assertThat(v2.correlationId()).isEqualTo("C9");               // found despite the empty version
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/base/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);      // nested, mixed-case "ResponseCode":0
        });
    }

    @Test
    void apiExercisedOnlyOnAnotherReleaseIsNotTestedWithDiagnostic() throws IOException {
        // The log has the API only at 9.3; analysing for 9.4 ⇒ matched but wrong
        // release, and the note must say so (the diagnostic that explains "not tested").
        LogAnalysisReport r = analyze("analysis-otherversion.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.NOT_TESTED);
        assertThat(v2.note()).contains("versions seen").contains("9.3");
    }

    @Test
    void missingFrontEndResponseIsTimeout() throws IOException {
        LogAnalysisReport r = analyze("analysis-timeout.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.TIMEOUT);
        assertThat(v2.note()).containsIgnoringCase("no response");
    }

    @Test
    void frontEndOkButBackendFailedIsPartial() throws IOException {
        LogAnalysisReport r = analyze("analysis-partial.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.FAILED);   // a release change flow was exercised and failed (Q1)
        assertThat(v2.responseCode()).matches("0+");   // front end itself was green
        BackendCallResult own = v2.backends().stream()
                .filter(b -> b.backend().contains("/bfs/ft/own/submit")).findFirst().orElseThrow();
        assertThat(own.status()).isEqualTo(LogStatus.FAILED);
        assertThat(own.responseCode()).isEqualTo("00911");
    }

    @Test
    void splunkCsvExportYieldsTheSameVerdictAsTheRawLog() throws IOException {
        LogAnalysisReport r = analyze("analysis-splunk.csv", "9.4");

        assertThat(r.uploadType()).isEqualTo("SPLUNK_CSV");   // auto-detected from the header
        assertThat(r.transactions()).isEqualTo(1);            // _raw extracted from every CSV row
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.feLatencyMs()).isEqualTo(500);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void splunkRawCsvExportGivesTheSameVerdictAsUploadingTheRawLog() throws IOException {
        // The _raw-only Splunk export is just the raw log lines wrapped as CSV, so verifying
        // it must produce exactly the same result as uploading the raw output log itself.
        String rawLog = Files.readString(Path.of("src/test/resources/sample-logs/analysis-e2e.log"));
        LogAnalysisReport rawReport = analyzeText(rawLog, "9.4");
        LogAnalysisReport csvReport = analyzeText(toRawCsv(rawLog), "9.4");

        assertThat(rawReport.uploadType()).isEqualTo("RAW_LOG");
        assertThat(csvReport.uploadType()).isEqualTo("SPLUNK_CSV");
        assertSameVerdict(rawReport, csvReport);
    }

    @Test
    void splunkRawCsvExportGivesTheSameVerdictAsTheRawLogForSplApp() throws IOException {
        // Same equivalence for the SPL application (SPLMessage / SPLHostMessage markers):
        // a _raw-only export must verify identically to uploading the raw SPL output log.
        String rawLog = Files.readString(Path.of("src/test/resources/sample-logs/analysis-spl-app.log"));
        LogAnalysisReport rawReport = analyzeText(rawLog, "9.4", "SPL");
        LogAnalysisReport csvReport = analyzeText(toRawCsv(rawLog), "9.4", "SPL");

        assertThat(csvReport.uploadType()).isEqualTo("SPLUNK_CSV");
        assertSameVerdict(rawReport, csvReport);
        assertThat(api(csvReport, V2).status()).isEqualTo(LogStatus.PARTIAL);   // SPL markers matched (own/submit covered)
    }

    @Test
    void splResponseCode200ReadsAsSuccess() throws IOException {
        // Some SPL modules log success as a BUSINESS "responseCode": "200" instead of all-zeros — on both the
        // SPLHostMessage backend and the SPLMessage front-end response. We read the business responseCode (not
        // the HTTP status), so both 200s must verify as SUCCESS.
        String log =
            "2026-06-11 18.43.45.102 [t] INFO [SPLMessage][MTY][s][u][9.4][C1][Android][]-/mty-banking-01/services/sg/payment/v2/fund/submit -Request - {\"serviceRequest\":{}}\n"
          + "2026-06-11 18.43.45.318 [t] INFO [SPLHostMessage][MTY][s][u][9.4][C1][Android][230ms]-/mty-banking-01/bfs/ft/own/submit -[Response] - {\"serviceResponse\":{\"responseCode\":\"200\",\"responseDescription\":\"OK\"}}\n"
          + "2026-06-11 18.43.45.502 [t] INFO [SPLMessage][MTY][s][u][9.4][C1][Android][500ms]-/mty-banking-01/services/sg/payment/v2/fund/submit -Response - {\"serviceResponse\":{\"responseCode\":\"200\",\"responseDescription\":\"OK\"}}\n";

        LogAnalysisReport r = analyzeText(log, "9.4", "SPL");
        ApiLogResult v2 = api(r, V2);
        // PARTIAL (not FAILED) confirms the FE 200 read as success; the own/submit backend row confirms the
        // BE 200. The other release branches are untested, so the API rolls up to PARTIAL rather than SUCCESS.
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void allFourCombosOfAllZerosAnd200ForFeAndBeReadAsSuccess() throws IOException {
        // The SPL success contract: FE and BE each signal success with EITHER all-zeros OR "200", in any
        // combination — FE 200/BE 200, FE 0000000/BE 200, FE 200/BE 00000, FE 0000000/BE 00000. All succeed.
        String[][] combos = {
                {"200", "200"}, {"0000000", "200"}, {"200", "00000"}, {"0000000", "00000"},
        };
        for (String[] c : combos) {
            String feCode = c[0], beCode = c[1];
            String log =
                "2026-06-11 18.43.45.102 [t] INFO [SPLMessage][MTY][s][u][9.4][C1][Android][]-/mty-banking-01/services/sg/payment/v2/fund/submit -Request - {\"serviceRequest\":{}}\n"
              + "2026-06-11 18.43.45.318 [t] INFO [SPLHostMessage][MTY][s][u][9.4][C1][Android][230ms]-/mty-banking-01/bfs/ft/own/submit -[Response] - {\"serviceResponse\":{\"responseCode\":\"" + beCode + "\"}}\n"
              + "2026-06-11 18.43.45.502 [t] INFO [SPLMessage][MTY][s][u][9.4][C1][Android][500ms]-/mty-banking-01/services/sg/payment/v2/fund/submit -Response - {\"serviceResponse\":{\"responseCode\":\"" + feCode + "\"}}\n";
            LogAnalysisReport r = analyzeText(log, "9.4", "SPL");
            ApiLogResult v2 = api(r, V2);
            // PARTIAL (not FAILED) proves the FE code read as success; the own/submit row proves the BE code.
            assertThat(v2.status()).as("FE=%s BE=%s", feCode, beCode).isEqualTo(LogStatus.PARTIAL);
            assertThat(v2.backends()).as("FE=%s BE=%s", feCode, beCode).anySatisfy(b -> {
                assertThat(b.backend()).contains("/bfs/ft/own/submit");
                assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
            });
        }
    }

    private void assertSameVerdict(LogAnalysisReport raw, LogAnalysisReport csv) {
        assertThat(csv.transactions()).isEqualTo(raw.transactions());
        assertThat(csv.matchedLines()).isEqualTo(raw.matchedLines());
        Map<String, LogStatus> rawByApi = raw.apis().stream()
                .collect(Collectors.toMap(ApiLogResult::api, ApiLogResult::status));
        Map<String, LogStatus> csvByApi = csv.apis().stream()
                .collect(Collectors.toMap(ApiLogResult::api, ApiLogResult::status));
        assertThat(csvByApi).isEqualTo(rawByApi);
    }

    private LogAnalysisReport analyzeText(String content, String version) throws IOException {
        return analyzeText(content, version, "Mighty");
    }

    private LogAnalysisReport analyzeText(String content, String version, String app) throws IOException {
        try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            return service.analyze(in, "in.txt", version, null, FW, null, null, true, app);
        }
    }

    /** The _raw-only Splunk CSV export equivalent to a raw log (each line one row). */
    private static String toRawCsv(String rawLog) {
        StringBuilder csv = new StringBuilder("_raw\n");
        for (String ln : rawLog.split("\n", -1)) {
            if (ln.isBlank()) continue;
            csv.append('"').append(ln.replace("\"", "\"\"")).append("\"\n");
        }
        return csv.toString();
    }

    @Test
    void splunkCsvSkipsHeaderAndNonEventRowsGenerically() throws IOException {
        // The _raw header row and any stray non-event rows (a repeated header, a blank line,
        // a garbage line with no marker) must be skipped without aborting the scan — only real
        // marker lines are parsed, so the verdict is unchanged.
        String rawLog = Files.readString(Path.of("src/test/resources/sample-logs/analysis-e2e.log"));
        LogAnalysisReport clean = analyzeText(rawLog, "9.4");

        String withJunk = toRawCsv(rawLog)
                .replaceFirst("\n", "\n\"_raw\"\n\n\"garbage line with no marker\"\n");
        LogAnalysisReport junked = analyzeText(withJunk, "9.4");

        assertThat(junked.uploadType()).isEqualTo("SPLUNK_CSV");
        assertSameVerdict(clean, junked);
    }

    @Test
    void splunkCsvWithOnlyARawColumnIsParsed() throws IOException {
        // The "| table _raw" query exports a single _raw column (no _time). It must still
        // auto-detect as SPLUNK_CSV and pull the event from column 0.
        LogAnalysisReport r = analyze("analysis-splunk-rawonly.csv", "9.4");

        assertThat(r.uploadType()).isEqualTo("SPLUNK_CSV");
        assertThat(r.transactions()).isEqualTo(1);
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void splunkCsvWithMultiLineRawFieldsIsReassembled() throws IOException {
        // Real Splunk exports quote the _raw event and let it span several physical lines
        // (embedded newlines). The CSV must be split on whole records, not readLine, or the
        // event is torn apart and the verdict is wrong. Same result as the single-line CSV.
        LogAnalysisReport r = analyze("analysis-splunk-multiline.csv", "9.4");

        assertThat(r.uploadType()).isEqualTo("SPLUNK_CSV");
        assertThat(r.transactions()).isEqualTo(1);
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // response JSON reassembled; own/submit covered
        assertThat(v2.feLatencyMs()).isEqualTo(500);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void backendOnlySelectionReadsHostMessageLines() throws IOException {
        // Select a backend, no front-end API → a per-backend report driven by the
        // MightyHostMessage lines (matched through the /mty-banking-01/ context prefix).
        LogAnalysisReport r = analyzeBackends("analysis-realistic.log", "9.4",
                List.of("{{baseUrl}}/bfs/ft/own/submit"));

        assertThat(r.apis()).isEmpty();          // no front-end section
        assertThat(r.backends()).hasSize(1);
        BackendLogResult be = r.backends().get(0);
        assertThat(be.tested()).isTrue();
        assertThat(be.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(be.latencyMs()).isEqualTo(230);
        assertThat(be.responseCode()).isEqualTo("0000000");
    }

    @Test
    void backendServiceVersionMatchIsOk() throws IOException {
        // own/submit is traced with service version 2.2; the log calls it at 2.2.
        LogAnalysisReport r = analyzeBackends("analysis-svcmatch.log", "9.4",
                List.of("{{baseUrl}}/bfs/ft/own/submit"));

        BackendLogResult be = r.backends().get(0);
        assertThat(be.expectedServiceVersion()).isEqualTo("2.2");
        assertThat(be.loggedServiceVersion()).isEqualTo("2.2");
        assertThat(be.serviceVersionOk()).isTrue();
    }

    @Test
    void backendMatchesEvenWhenPlaceholderResolvesToAMultiSegmentPrefix() throws IOException {
        // {{dge.bfs.XX}}/bfs/ft/own/submit — the placeholder resolves in the log to a
        // host + a MULTI-segment context (/gateway/dge/bfs-svc/…). The observed path
        // must still match by suffix, not be dropped as "not tested".
        LogAnalysisReport r = analyze("analysis-mseg.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);   // matched despite the long resolved prefix
        });
    }

    @Test
    void longestMatchKeepsShortAndLongBackendPathsApart() throws IOException {
        // /bfs/ft/own/submit (ok) and /bp/bfs/ft/own/submit (fail) both end in the same
        // suffix. With both backends in play, longest-match wins: each line is attributed
        // to its own backend, not the shorter one stealing the longer's call.
        LogAnalysisReport r = analyzeBackends("analysis-pathseg.log", "9.4",
                List.of("{{baseUrl}}/bfs/ft/own/submit", "{{baseUrl}}/bp/bfs/ft/own/submit"));

        BackendLogResult bfs = r.backends().stream()
                .filter(b -> b.backend().endsWith("/bfs/ft/own/submit") && !b.backend().contains("/bp/")).findFirst().orElseThrow();
        BackendLogResult bp = r.backends().stream()
                .filter(b -> b.backend().contains("/bp/bfs/ft/own/submit")).findFirst().orElseThrow();
        assertThat(bfs.status()).isEqualTo(LogStatus.SUCCESS);   // the /bfs/… line (0000000)
        assertThat(bfs.attempts()).isEqualTo(1);
        assertThat(bp.status()).isEqualTo(LogStatus.FAILED);     // the /bp/bfs/… line (00999)
        assertThat(bp.attempts()).isEqualTo(1);
    }

    @Test
    void backendMatchedByUrlAndServiceVersionTogether() throws IOException {
        // Two host lines share a correlation id and both END WITH /bfs/ft/own/submit:
        // /bp/bfs/… at svc 9.9 and /bfs/… at svc 2.2. The traced own/submit expects 2.2,
        // so the URL+svc pair must pick the 2.2 line — not the first path match.
        LogAnalysisReport r = analyzeBackends("analysis-svcpick.log", "9.4",
                List.of("{{baseUrl}}/bfs/ft/own/submit"));

        BackendLogResult be = r.backends().get(0);
        assertThat(be.expectedServiceVersion()).isEqualTo("2.2");
        assertThat(be.loggedServiceVersion()).isEqualTo("2.2");
        assertThat(be.serviceVersionOk()).isTrue();
        assertThat(be.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(be.latencyMs()).isEqualTo(230);
    }

    @Test
    void backendServiceVersionMismatchIsFlagged() throws IOException {
        // The log calls own/submit at 9.9 but the tracer expects 2.2 — flag it.
        LogAnalysisReport r = analyzeBackends("analysis-svcmismatch.log", "9.4",
                List.of("{{baseUrl}}/bfs/ft/own/submit"));

        BackendLogResult be = r.backends().get(0);
        assertThat(be.expectedServiceVersion()).isEqualTo("2.2");
        assertThat(be.loggedServiceVersion()).isEqualTo("9.9");
        assertThat(be.serviceVersionOk()).isFalse();
        assertThat(be.note()).contains("Service version mismatch").contains("9.9").contains("2.2");
    }

    @Test
    void splApplicationUsesSplMarkersNotMighty() throws IOException {
        // The SPL app's lines use SPLMessage / SPLHostMessage — analysed as SPL they
        // resolve; analysed as Mighty (different markers) they are ignored.
        LogAnalysisReport spl = analyze("analysis-spl-app.log", "9.4", "SPL");
        // Resolved as SPL: own/submit is covered (PARTIAL — the sibling branches were not exercised).
        assertThat(api(spl, V2).status()).isEqualTo(LogStatus.PARTIAL);

        LogAnalysisReport mighty = analyze("analysis-spl-app.log", "9.4", "Mighty");
        assertThat(api(mighty, V2).status()).isEqualTo(LogStatus.NOT_TESTED);
    }

    @Test
    void splunkJsonExportYieldsTheSameVerdictAsTheRawLog() throws IOException {
        LogAnalysisReport r = analyze("analysis-splunk.json", "9.4");

        assertThat(r.uploadType()).isEqualTo("SPLUNK_JSON");
        assertThat(r.transactions()).isEqualTo(1);
        assertThat(api(r, V2).status()).isEqualTo(LogStatus.PARTIAL);   // own/submit covered; sibling branches not tested
    }
}
