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
}
