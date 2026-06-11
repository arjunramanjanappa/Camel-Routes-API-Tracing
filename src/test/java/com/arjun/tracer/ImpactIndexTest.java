package com.arjun.tracer;

import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.ImpactIndex;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The impact catalog lists each API's routes/backends so the UI can find impacted APIs. */
class ImpactIndexTest {

    private final RouteTraceService service =
            new RouteTraceService("src/test/resources/sample-framework");

    @Test
    void buildsPerApiFootprintAndUnions() {
        ImpactIndex idx = service.impactIndex(new TraceRequest(null, "9.4", null, null));

        // Both controller endpoints are present.
        assertThat(idx.getApis()).extracting(ApiImpact::operation)
                .contains("fundTransferSubmitV2Api", "fundTransferSubmitApi");

        ApiImpact v2 = idx.getApis().stream()
                .filter(a -> a.operation().equals("fundTransferSubmitV2Api")).findFirst().orElseThrow();
        assertThat(v2.resolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        assertThat(v2.routes()).contains("R9.4_fundTransferSubmitV2Api", "R9.4_masterFundTransferSubmitApi");
        assertThat(v2.backends()).contains(
                "{{baseUrl}}/bfs/ft/own/submit", "{{baseUrl}}/bfs/ft/inter/submit");

        // Unions for the UI's change pickers.
        assertThat(idx.getAllBackends()).contains("{{baseUrl}}/bfs/ft/inter/fraud-check");
        assertThat(idx.getAllRoutes()).contains("R9.4_ftInterbankProcessSubmitDGEApi");
    }

    @Test
    void sharedHostCallRoutesAreExcludedFromRouteFootprint() {
        ImpactIndex idx = service.impactIndex(new TraceRequest(null, "9.4", null, null));

        // callUFWDGE is a per-call terminal route (route:callUFWDGE#N) reused by every
        // branch/version — it must NOT appear in the route change-picker (else marking
        // it changed would falsely impact every API).
        assertThat(idx.getAllRoutes()).noneMatch(r -> r.contains("callUFWDGE"));
        assertThat(idx.getApis()).allSatisfy(a ->
                assertThat(a.routes()).noneMatch(r -> r.contains("callUFWDGE")));

        // But real business routes are still listed, and the backend it calls still
        // carries the per-API impact signal — so we excluded the shared route without
        // losing coverage.
        assertThat(idx.getAllRoutes()).contains("R9.4_masterFundTransferSubmitApi");
        assertThat(idx.getAllBackends()).contains("{{baseUrl}}/bfs/ft/own/submit");
    }
}
