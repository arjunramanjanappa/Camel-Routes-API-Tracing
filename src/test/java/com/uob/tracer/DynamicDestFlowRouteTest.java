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
 * Dynamic direct:${FINAL_ROUTE_NAME} dispatch: two &lt;when&gt;s each set a DEST_ROUTE and resolve to
 * R9.14_manualauthDetails / R9.14_basicdetails, which BOTH call the shared R9.14_doUpdate → the same backend
 * /api/application/v3/update (svc 6.0). The results must show the FULL branch path (branchRoute → owning route
 * → backend) and — because the shared route is walked PER branch — the two branches are two distinct flows that
 * must EACH be covered. The log exercises only branch A, so the API is PARTIAL with branch B named.
 */
class DynamicDestFlowRouteTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-dyndest");

    private static final String LOG =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/v1/prospect/create -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/api/application/v3/update -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"6.0\"}}\n"
      + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/v1/prospect/create -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    // Two transactions, two calls to the shared backend at 6.0 — enough to cover BOTH branch flows (the shared
    // backend line carries no branch, so coverage is by matching-call COUNT: K flows need K calls).
    private static final String LOG_TWO_CALLS =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/v1/prospect/create -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/api/application/v3/update -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"6.0\"}}\n"
      + "2026-06-11 18.43.45.402 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/v1/prospect/create -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
      + "2026-06-11 18.43.46.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C2][IOS][]-/v1/prospect/create -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.46.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C2][IOS][240ms]-/api/application/v3/update -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"6.0\"}}\n"
      + "2026-06-11 18.43.46.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C2][IOS][500ms]-/v1/prospect/create -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    private ApiLogResult analyze() throws IOException { return analyze(LOG); }

    private ApiLogResult analyze(String log) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-dyndest", null, null, true, "Mighty");
            return rep.apis().stream().filter(a -> "prospectCreateV1".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("prospectCreateV1 not in the report"));
        }
    }

    // Two transactions that BOTH took the manualauth branch — each carries the <when> constant MANUALAUTH as a
    // bracket field. Count-based alone would call this SUCCESS (2 calls = both flows); branch-aware must see
    // that only manualauth was actually exercised and keep basicdetails Not-tested.
    private static final String LOG_BOTH_MANUALAUTH =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][MANUALAUTH][]-/v1/prospect/create -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][MANUALAUTH][230ms]-/api/application/v3/update -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"6.0\"}}\n"
      + "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][MANUALAUTH][500ms]-/v1/prospect/create -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n"
      + "2026-06-11 18.43.46.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C2][IOS][MANUALAUTH][]-/v1/prospect/create -Request - {\"serviceRequest\":{}}\n"
      + "2026-06-11 18.43.46.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C2][IOS][MANUALAUTH][240ms]-/api/application/v3/update -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"6.0\"}}\n"
      + "2026-06-11 18.43.46.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C2][IOS][MANUALAUTH][500ms]-/v1/prospect/create -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    @Test
    void branchAwareCoverageAttributesByTheWhenConstantInTheLog() throws IOException {
        ApiLogResult apiA = analyze(LOG_BOTH_MANUALAUTH);
        // manualauth was actually taken (both txns tagged MANUALAUTH) → covered.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.branchRoute()).isEqualTo("R9.14_manualauthDetails");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // basicdetails was NEVER taken (no BASICAUTH transaction) → Not tested, even though 2 backend calls exist.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.branchRoute()).isEqualTo("R9.14_basicdetails");
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);
        });
        assertThat(apiA.status()).isEqualTo(LogStatus.PARTIAL);
    }

    @Test
    void twoCallsToTheSharedBackendCoverBothBranchFlows() throws IOException {
        ApiLogResult apiA = analyze(LOG_TWO_CALLS);
        // Both branch flows are on the same backend+version, so two matching calls cover both → clean SUCCESS.
        assertThat(apiA.backends())
                .filteredOn(b -> b.backend() != null && b.backend().contains("/api/application/v3/update"))
                .hasSize(2)
                .allSatisfy(b -> assertThat(b.status()).isEqualTo(LogStatus.SUCCESS));
        assertThat(apiA.status()).isEqualTo(LogStatus.SUCCESS);
    }

    @Test
    void eachDynamicBranchIsItsOwnFlowLabelledWithTheFullBranchPath() throws IOException {
        ApiLogResult apiA = analyze();

        // Branch A (manualauthDetails) reached /api/application/v3/update 6.0 via the shared R9.14_doUpdate — covered.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.branchRoute()).isEqualTo("R9.14_manualauthDetails");
            assertThat(b.flowRoute()).isEqualTo("R9.14_doUpdate");
            assertThat(b.backend()).contains("/api/application/v3/update");
            assertThat(b.expectedServiceVersion()).isEqualTo("6.0");
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // Branch B (basicdetails) — same shared route + backend, but NOT exercised → its own Not-Tested flow.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.branchRoute()).isEqualTo("R9.14_basicdetails");
            assertThat(b.flowRoute()).isEqualTo("R9.14_doUpdate");
            assertThat(b.backend()).contains("/api/application/v3/update");
            assertThat(b.status()).isEqualTo(LogStatus.NOT_TESTED);
        });

        // Both branches are required to cover the shared backend, so one untested branch makes the API PARTIAL,
        // and the reason names the missed branch with its full path.
        assertThat(apiA.status()).isEqualTo(LogStatus.PARTIAL);
        assertThat(apiA.note()).contains("R9.14_basicdetails").contains("R9.14_doUpdate").contains("/api/application/v3/update");
    }
}
