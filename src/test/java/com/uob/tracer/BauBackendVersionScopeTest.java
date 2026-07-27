package com.uob.tracer;

import com.uob.tracer.api.ApiImpact;
import com.uob.tracer.api.ImpactIndex;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

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
}
