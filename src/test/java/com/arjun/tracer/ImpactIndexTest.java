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
    void buildsPerApiFootprintScopedToTheRelease() {
        ImpactIndex idx = service.impactIndex(new TraceRequest(null, "9.4", null, null));

        // Only the APIs that release 9.4 actually impacts (resolve to R9.4) are listed;
        // the v1 endpoint has no route and is excluded with a notice.
        assertThat(idx.getApis()).extracting(ApiImpact::operation)
                .containsExactlyInAnyOrder("fundTransferSubmitV2Api", "limitInitiateApi", "comboApi", "dualApi");
        assertThat(idx.getWarnings()).anyMatch(w -> w.contains("not impacted by version 9.4"));

        ApiImpact v2 = idx.getApis().stream()
                .filter(a -> a.operation().equals("fundTransferSubmitV2Api")).findFirst().orElseThrow();
        assertThat(v2.resolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        assertThat(v2.routes()).contains("R9.4_fundTransferSubmitV2Api", "R9.4_masterFundTransferSubmitApi");
        assertThat(v2.backends()).contains(
                "{{baseUrl}}/bfs/ft/own/submit", "{{baseUrl}}/bfs/ft/inter/submit");

        // Unions for the UI's change pickers — scoped to the 9.4 footprint.
        assertThat(idx.getAllBackends()).contains("{{baseUrl}}/bfs/ft/inter/fraud-check");
        assertThat(idx.getAllRoutes()).contains("R9.4_ftInterbankProcessSubmitDGEApi");
        assertThat(idx.getAllRoutes()).allMatch(r -> r.startsWith("R9.4_"));   // only 9.4 routes
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

    @Test
    void mapsEachBusinessRouteToTheBackendsItReaches() {
        ImpactIndex idx = service.impactIndex(new TraceRequest(null, "9.4", null, null));

        // limitInitiate reaches its backend THROUGH the shared callUFWDGE route, so the
        // backend must still be attributed to the nearest business route (the entry one),
        // not lost with the excluded shared route. This drives the UI's route → backend
        // auto-selection.
        assertThat(idx.getRouteBackends())
                .containsEntry("R9.4_limitInitiateApi", java.util.List.of("/asv/transaction/limit/initiate"));

        // A route that delegates to sub-routes has no backend of its own; its backends
        // belong to the sub-routes that actually call them.
        assertThat(idx.getRouteBackends().get("R9.4_ftOwnAccountProcessSubmitDGEApi"))
                .containsExactly("{{baseUrl}}/bfs/ft/own/submit");
        assertThat(idx.getRouteBackends()).doesNotContainKey("callUFWDGE");
    }
}
