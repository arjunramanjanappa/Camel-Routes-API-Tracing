package com.arjun.tracer;

import com.arjun.tracer.api.ApiLogResult;
import com.arjun.tracer.api.BackendCallResult;
import com.arjun.tracer.api.BackendLogResult;
import com.arjun.tracer.api.LogAnalysisReport;
import com.arjun.tracer.api.LogStatus;
import com.arjun.tracer.service.LogAnalysisService;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void successfulTransactionIsGreenAndPicksLatestAttempt() throws IOException {
        LogAnalysisReport r = analyze("analysis-mixed.log", "9.4");

        // Pre-filter ignored the no-marker line; 10 marker lines parsed into 3 txns.
        assertThat(r.matchedLines()).isEqualTo(10);
        assertThat(r.transactions()).isEqualTo(3);

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);   // latest (C1) succeeded
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
    void versionAndContextPathFoundByPatternNotPosition() throws IOException {
        // Real-world shape: a /mty-banking-01/ context prefix and extra bracket
        // fields push the version off its "expected" slot (here it sits at index 5,
        // after MTY/channel/session/user/device). Pattern matching must still find
        // 9.4 and match the API by path suffix — otherwise everything reads "not tested".
        LogAnalysisReport r = analyze("analysis-realistic.log", "9.4");

        ApiLogResult v2 = api(r, V2);
        assertThat(v2.tested()).isTrue();
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(v2.correlationId()).isEqualTo("C9");   // the field right after the version
        assertThat(v2.feLatencyMs()).isEqualTo(500);      // the 500ms-shaped field
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
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(v2.correlationId()).isEqualTo("C9");
        assertThat(v2.feLatencyMs()).isEqualTo(500);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }

    @Test
    void jwtHostLineWithoutARealPathIsIgnored() throws IOException {
        // A "[jwt]: true, <url>" host line carries no real path and must be ignored —
        // it must not be parsed nor create a spurious backend call. The genuine
        // backend response (own/submit) on a normal line is still matched.
        LogAnalysisReport r = analyze("analysis-jwt.log", "9.4");

        assertThat(r.matchedLines()).isEqualTo(3);     // FE req + BE resp + FE resp; jwt line excluded
        assertThat(r.unparsedLines()).isEqualTo(1);    // the jwt line
        ApiLogResult v2 = api(r, V2);
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);
        assertThat(v2.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/bfs/ft/own/submit");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
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
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);
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
        assertThat(v2.status()).isEqualTo(LogStatus.PARTIAL);
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
        assertThat(v2.status()).isEqualTo(LogStatus.SUCCESS);
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
        assertThat(api(spl, V2).status()).isEqualTo(LogStatus.SUCCESS);

        LogAnalysisReport mighty = analyze("analysis-spl-app.log", "9.4", "Mighty");
        assertThat(api(mighty, V2).status()).isEqualTo(LogStatus.NOT_TESTED);
    }

    @Test
    void splunkJsonExportYieldsTheSameVerdictAsTheRawLog() throws IOException {
        LogAnalysisReport r = analyze("analysis-splunk.json", "9.4");

        assertThat(r.uploadType()).isEqualTo("SPLUNK_JSON");
        assertThat(r.transactions()).isEqualTo(1);
        assertThat(api(r, V2).status()).isEqualTo(LogStatus.SUCCESS);
    }
}
