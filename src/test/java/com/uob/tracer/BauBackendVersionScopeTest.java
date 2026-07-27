package com.uob.tracer;

import com.uob.tracer.api.ApiImpact;
import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.ImpactIndex;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SAME backend (/bfs/ft/initiate) is called by R9.14_apiB (this release's change, serviceVersionNumber 4.0)
 * AND by the BAU R8.8_apiC (reuse, 2.5). Release-test verification must expect only the change's 4.0 — the BAU
 * 2.5 is an unchanged 8.8 route and is not verified — while the full footprint still records both for the graph.
 */
class BauBackendVersionScopeTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-reuse");

    @Test
    void changeScopeCoversTheReleaseFlowsAndExcludesBauReuseOfTheSameBackend() {
        // R9.14_apiA → R9.14_routeB (/getStatus 4.0) + R9.14_routeC (/fetchStatus 4.1) + R9.8_routeC (/getStatus 2.0 BAU).
        ImpactIndex idx = svc.impactIndex(new TraceRequest(null, "9.14", null, null, null, null, null, null));
        ApiImpact apiA = idx.getApis().stream()
                .filter(a -> "apiA".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("apiA not in the impact index"));

        // Full footprint still records everything (graph / release diff): /getStatus is called at 4.0 AND 2.0.
        assertThat(apiA.backendVersions()).containsEntry("/getStatus", "4.0 / 2.0");
        assertThat(apiA.backendVersions()).containsEntry("/fetchStatus", "4.1");

        // Release-test CHANGE scope: only the 9.14 flows — /getStatus 4.0 (R9.14_routeB) and /fetchStatus 4.1
        // (R9.14_routeC). The BAU 9.8 flow (/getStatus 2.0 via R9.8_routeC) is excluded from what's verified.
        assertThat(apiA.changeBackendVersions()).containsEntry("/getStatus", "4.0");
        assertThat(apiA.changeBackendVersions()).containsEntry("/fetchStatus", "4.1");
        assertThat(apiA.changeBackendVersions().get("/getStatus")).doesNotContain("2.0");
    }

    @Test
    void changeBackendVerifiedAndBauReuseGetsItsOwnRow() throws IOException {
        // Log exercises /getStatus at 4.0 (the change) and /fetchStatus at 4.1 — but NOT /getStatus at 2.0 (BAU).
        String log =
            "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/services/sg/status -Request - {\"serviceRequest\":{}}\n"
          + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/getStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.0\"}}\n"
          + "2026-06-11 18.43.45.320 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][240ms]-/fetchStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.1\"}}\n"
          + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/services/sg/status -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

        LogAnalysisReport rep;
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-reuse", null, null, true, "Mighty");
        }
        ApiLogResult apiA = rep.apis().stream()
                .filter(a -> "apiA".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("apiA not in the report"));

        // The 9.14 change /getStatus 4.0 is VERIFIED (its own row, not BAU) and passed.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/getStatus");
            assertThat(b.expectedServiceVersion()).isEqualTo("4.0");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // The SAME backend's BAU reuse at 2.0 is a SEPARATE row, labelled BAU, not tested (no log for that svc).
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/getStatus");
            assertThat(b.expectedServiceVersion()).isEqualTo("2.0");
            assertThat(b.bau()).isTrue();
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);   // "BAU – no logs found"
        });
        // /fetchStatus 4.1 change verified.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/fetchStatus");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // The BAU no-log row must NOT drag the API's verdict down.
        assertThat(apiA.status()).isEqualTo(LogStatus.SUCCESS);
    }
}
