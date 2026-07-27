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
 * Scenario 6: R9.14_routeX and R9.14_routeY are TWO distinct release flows that call the SAME backend
 * (/shared) at the SAME service version (2.0) — indistinguishable in the log. Both must be covered, so a
 * single matching call leaves one flow Not Tested (PARTIAL); two matching calls (two OR trace ids) cover
 * both (SUCCESS). Coverage is by matching-call COUNT, not by a single latest hit.
 */
class TwinFlowCoverageTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/flow-twin");

    private static String feReq(String corr) {
        return "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][" + corr
                + "][IOS][]-/services/sg/twin -Request - {\"serviceRequest\":{}}\n";
    }

    private static String beShared(String corr, String ms) {
        return "2026-06-11 18.43.45." + ms + " [t] INFO [MightyHostMessage][MTY][s][u][9.14][" + corr
                + "][IOS][230ms]-/shared -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"2.0\"}}\n";
    }

    private static String feResp(String corr) {
        return "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][" + corr
                + "][IOS][500ms]-/services/sg/twin -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";
    }

    private ApiLogResult analyze(String log) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/flow-twin", null, null, true, "Mighty");
            return rep.apis().stream().filter(a -> "apiT".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("apiT not in the report"));
        }
    }

    @Test
    void oneCallCoversOnlyOneOfTwoTwinFlowsSoTheApiIsPartial() throws IOException {
        // A single transaction hits /shared once — that covers just one of the two release flows on it.
        ApiLogResult apiT = analyze(feReq("C1") + beShared("C1", "318") + feResp("C1"));

        // Two rows for /shared (one per release route), labelled distinctly by their route.
        assertThat(apiT.backends()).filteredOn(b -> b.backend().contains("/shared")).hasSize(2);
        assertThat(apiT.backends()).filteredOn(b -> b.backend().contains("/shared"))
                .extracting(b -> b.flowRoute()).contains("R9.14_routeX", "R9.14_routeY");
        // One covered, one Not Tested → PARTIAL.
        assertThat(apiT.backends()).filteredOn(b -> b.backend().contains("/shared"))
                .anySatisfy(b -> assertThat(b.status()).isEqualTo(LogStatus.SUCCESS))
                .anySatisfy(b -> assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED));
        assertThat(apiT.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(apiT.note()).contains("Change flow not tested");
    }

    @Test
    void twoCallsAcrossTwoTraceIdsCoverBothTwinFlowsSoTheApiIsSuccess() throws IOException {
        // Two separate transactions each hit /shared once — two matching calls cover both twin flows.
        ApiLogResult apiT = analyze(
                feReq("C1") + beShared("C1", "318") + feResp("C1")
                + feReq("C2") + beShared("C2", "620") + feResp("C2"));

        assertThat(apiT.backends()).filteredOn(b -> b.backend().contains("/shared"))
                .allSatisfy(b -> assertThat(b.status()).isEqualTo(LogStatus.SUCCESS));
        assertThat(apiT.status()).isEqualTo(LogStatus.SUCCESS);
    }
}
