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
 * The wealth/orderplacement case: a 9.14 route reaches a resolved-down BAU route (R8.16_utAccount) whose backend
 * sets NO serviceVersionNumber. The 9.14 change flow (/precapture svc 2.3) passes; the BAU /utAccount backend
 * fails. A BAU failure must NOT fail the new-app verdict — but today BAU is detected by the service-version
 * template, so a lower-version route with no template of its own is scored as a 9.14 CHANGE flow and its failure
 * flips the API to FAILED. Front-end is all-success here, so this is independent of the pass-rate threshold.
 */
class BauNoVersionRouteVerdictTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-bau-noversion");

    // One transaction: the front end succeeds; /precapture (9.14, svc 2.3) succeeds; the BAU /utAccount backend
    // (R8.16, no svc version) returns a failure code.
    private static final String LOG =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/services/sg/status -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/precapture -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"2.3\"}}\n"
      + "2026-06-11 18.43.45.402 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][900ms]-/utAccount -[Response] - {\"serviceResponse\":{\"responseCode\":\"9999999\"}}\n"
      + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/services/sg/status -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    private ApiLogResult analyze() throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(LOG.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-bau-noversion", null, null, true, "Mighty");
            return rep.apis().stream().filter(a -> "apiA".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("apiA not in the report"));
        }
    }

    @Test
    void aFailedBackendFromAResolvedDownRouteWithNoSvcVersionIsBauAndDoesNotFailTheVerdict() throws IOException {
        ApiLogResult apiA = analyze();

        // The 9.14 change flow /precapture 2.3 is verified.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/precapture");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });

        // /utAccount comes from R8.16 (resolved-down) and sets no service version → it must be BAU, so its
        // failure never enters the change verdict.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/utAccount");
            assertThat(b.bau()).isTrue();
        });

        // The whole point: the 9.14 change passed, so the API is SUCCESS despite the BAU backend failing.
        assertThat(apiA.status()).isEqualTo(LogStatus.SUCCESS);
    }
}
