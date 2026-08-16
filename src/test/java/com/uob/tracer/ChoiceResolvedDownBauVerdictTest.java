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
 * The user's exact topology: a NEW 9.14 route (R9.14_apiA) that always calls the 9.14 backend /myInfo, then a
 * {@code <choice>} whose {@code <otherwise>} resolves DOWN to a BAU route (R9.10_callGet → its own backend
 * /getLegacy 2.0). The release-test SCOPE is the change — /myInfo (4.0) and /fetch (4.1). The resolved-down
 * R9.10 /getLegacy 2.0 is BAU: it must be excluded from the change scope, and — crucially — an untested BAU
 * leg must NOT drag the API's verdict below SUCCESS when the 9.14 flows are covered and passing.
 *
 * Locks in the "focus testing on the new change, ignore unmodified BAU" verdict rule for the choice/otherwise
 * resolved-down shape (the same-backend-reuse shape is covered by {@link BauBackendVersionScopeTest}).
 */
class ChoiceResolvedDownBauVerdictTest {

    private final RouteTraceService svc = new RouteTraceService("src/test/resources/svc-choice-bau");

    private static final String FE_REQ =
        "2026-06-11 18.43.45.102 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][]-/services/sg/status -Request - {\"serviceRequest\":{}}\n";
    private static final String BE_MYINFO =
        "2026-06-11 18.43.45.318 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][230ms]-/myInfo -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.0\"}}\n";
    private static final String BE_FETCH =
        "2026-06-11 18.43.45.320 [t] INFO [MightyHostMessage][MTY][s][u][9.14][C1][IOS][240ms]-/fetch -[Response] - {\"serviceResponse\":{\"responseCode\":\"0000000\",\"serviceVersionNumber\":\"4.1\"}}\n";
    private static final String FE_RESP =
        "2026-06-11 18.43.45.502 [t] INFO [MightyMessage][MTY][s][u][9.14][C1][IOS][500ms]-/services/sg/status -Response - {\"serviceResponse\":{\"responseCode\":\"0000000\"}}\n";

    private ApiLogResult analyze(String log) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8))) {
            LogAnalysisReport rep = new LogAnalysisService(svc).analyze(in, "log.log", "9.14", null,
                    "src/test/resources/svc-choice-bau", null, null, true, "Mighty");
            return rep.apis().stream().filter(a -> "apiA".equals(a.operation()))
                    .findFirst().orElseThrow(() -> new AssertionError("apiA not in the report"));
        }
    }

    @Test
    void theResolvedDownR910FlowIsExcludedFromTheChangeScope() {
        ImpactIndex idx = svc.impactIndex(new TraceRequest(null, "9.14", null, null, null, null, null, null));
        ApiImpact apiA = idx.getApis().stream()
                .filter(a -> "apiA".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("apiA not in the impact index"));

        // Full footprint records every leg (graph / release diff) — including the resolved-down /getLegacy 2.0.
        assertThat(apiA.backendVersions()).containsEntry("/myInfo", "4.0");
        assertThat(apiA.backendVersions()).containsEntry("/fetch", "4.1");
        assertThat(apiA.backendVersions()).containsEntry("/getLegacy", "2.0");

        // CHANGE scope = only the 9.14 flows. The resolved-down R9.10 /getLegacy 2.0 is BAU, so it is NOT part
        // of what release-test must verify.
        assertThat(apiA.changeBackendVersions()).containsEntry("/myInfo", "4.0");
        assertThat(apiA.changeBackendVersions()).containsEntry("/fetch", "4.1");
        assertThat(apiA.changeBackendVersions()).doesNotContainKey("/getLegacy");
    }

    @Test
    void bauLegUntestedDoesNotDowngradeTheVerdictWhenChangeFlowsPass() throws IOException {
        // Log exercises the 9.14 change flows /myInfo 4.0 and /fetch 4.1 — but NOT the BAU /getLegacy 2.0.
        ApiLogResult apiA = analyze(FE_REQ + BE_MYINFO + BE_FETCH + FE_RESP);

        // The 9.14 change flows are verified.
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/myInfo");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        assertThat(apiA.backends()).anySatisfy(b -> {
            assertThat(b.backend()).contains("/fetch");
            assertThat(b.bau()).isFalse();
            assertThat(b.status()).isEqualTo(LogStatus.SUCCESS);
        });
        // Any /getLegacy row that appears is BAU (never a change flow) — so it can never be a change gap.
        assertThat(apiA.backends())
                .filteredOn(b -> b.backend() != null && b.backend().contains("/getLegacy"))
                .allSatisfy(b -> assertThat(b.bau()).isTrue());

        // The whole point: 9.14 flows covered + passing, BAU untested → the API is a clean SUCCESS.
        assertThat(apiA.status()).isEqualTo(LogStatus.SUCCESS);
    }
}
