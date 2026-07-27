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
 * Three distinct RELEASE-version (9.14) child flows: R9.14_routeB → /getStatus 4.0, R9.14_routeC →
 * /fetchStatus 4.1, R9.14_routeD → /putStatus 2.0. All are unconditional (no {@code <choice>} on the path),
 * so all three MUST be tested. If /putStatus is never in the log the API is PARTIAL, not a clean SUCCESS.
 */
class ReleaseFlowCoverageTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-multi");

    private static final String FE_REQ =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/services/sg/status -Request - {\"serviceRequest\":{}}\n";
    private static final String BE_GET =
        "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/getStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.0\"}}\n";
    private static final String BE_FETCH =
        "2026-06-11 18.43.45.320 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][240ms]-/fetchStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.1\"}}\n";
    private static final String BE_PUT =
        "2026-06-11 18.43.45.322 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][250ms]-/putStatus -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"2.0\"}}\n";
    private static final String FE_RESP =
        "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/services/sg/status -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    private ApiLogResult analyze(String log) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-multi", null, null, true, "Mighty");
            return rep.apis().stream().filter(a -> "apiA".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("apiA not in the report"));
        }
    }

    @Test
    void anUntestedUnconditionalReleaseBackendMakesTheApiPartial() throws IOException {
        // /getStatus 4.0 and /fetchStatus 4.1 tested; /putStatus 2.0 (a distinct 9.14 backend) is NOT in the log.
        ApiLogResult apiA = analyze(FE_REQ + BE_GET + BE_FETCH + FE_RESP);

        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/putStatus");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);
        });
        // A required release flow was never tested → the API is PARTIAL with a clear reason.
        assertThat(apiA.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(apiA.note()).contains("Change flow not tested").contains("/putStatus");
    }

    @Test
    void whenAllThreeReleaseFlowsAreTestedTheApiIsSuccess() throws IOException {
        // Now the log also exercises /putStatus 2.0 — every release flow covered.
        ApiLogResult apiA = analyze(FE_REQ + BE_GET + BE_FETCH + BE_PUT + FE_RESP);
        assertThat(apiA.status()).isEqualTo(LogStatus.SUCCESS);
    }
}
