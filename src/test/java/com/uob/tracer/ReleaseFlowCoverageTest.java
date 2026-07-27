package com.uob.tracer;

import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All three child flows are at the RELEASE version (9.14): R9.14_routeB → /getStatus 4.0, R9.14_routeC →
 * /fetchStatus 4.1 AND /getStatus 2.0. So /getStatus is called at TWO release versions (4.0 and 2.0) — neither
 * is BAU, both must be tested. They must appear as SEPARATE version-strict rows (not one collapsed row).
 */
class ReleaseFlowCoverageTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-multi");

    private LogAnalysisReport analyze(String log) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            return new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-multi", null, null, true, "Mighty");
        }
    }

    @Test
    void twoReleaseVersionsOfTheSameBackendAreSeparateRowsAndTheUntestedOneShows() throws IOException {
        // Log exercises /getStatus 4.0 and /fetchStatus 4.1, but NOT /getStatus 2.0.
        String log =
            "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/services/sg/status -Request - {\"serviceRequest\":{}}\n"
          + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/getStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.0\"}}\n"
          + "2026-06-11 18.43.45.320 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][240ms]-/fetchStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.1\"}}\n"
          + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/services/sg/status -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

        ApiLogResult apiA = analyze(log).apis().stream()
                .filter(a -> "apiA".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("apiA not in the report"));

        // /getStatus 4.0 — a release flow, verified, passed.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/getStatus");
            assertThat(b.expectedServiceVersion()).isEqualTo("4.0");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // /getStatus 2.0 — a SEPARATE release flow (NOT BAU), not observed in the log → Not Tested.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/getStatus");
            assertThat(b.expectedServiceVersion()).isEqualTo("2.0");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);
        });
        // /fetchStatus 4.1 — verified.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/fetchStatus");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
    }
}
