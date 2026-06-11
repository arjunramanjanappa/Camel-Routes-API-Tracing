package com.arjun.tracer;

import com.arjun.tracer.api.ApiLogResult;
import com.arjun.tracer.api.BackendCallResult;
import com.arjun.tracer.api.LogAnalysisReport;
import com.arjun.tracer.api.LogStatus;
import com.arjun.tracer.service.LogAnalysisService;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correlates synthetic output logs (src/test/resources/sample-logs) against the
 * traced footprint of the sample framework, covering every end-to-end verdict.
 */
class LogAnalysisServiceTest {

    private static final String FW = "src/test/resources/sample-framework";
    private static final String V2 = "/payment/v2/fund/submit";   // has routes at 9.4
    private static final String V1 = "/payment/v1/fund/submit";   // no route → never tested

    private final LogAnalysisService service = new LogAnalysisService(new RouteTraceService(FW));

    private LogAnalysisReport analyze(String logFile, String version) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/sample-logs/" + logFile))) {
            return service.analyze(in, logFile, version, null, FW, null);
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
    void apiWithNoEntryForThatReleaseIsNotTested() throws IOException {
        LogAnalysisReport r = analyze("analysis-mixed.log", "9.4");

        ApiLogResult v1 = api(r, V1);
        assertThat(v1.tested()).isFalse();
        assertThat(v1.status()).isEqualTo(LogStatus.NOT_TESTED);
        assertThat(v1.note()).containsIgnoringCase("never tested");
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
}
