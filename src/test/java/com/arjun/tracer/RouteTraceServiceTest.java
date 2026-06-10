package com.arjun.tracer;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks of the tracer against a synthetic framework fixture under
 * {@code src/test/resources/sample-framework} (test-only; not shipped).
 * Exercises the Camel RouteDefinition loader, version fallback, choice branching
 * and backend extraction.
 */
class RouteTraceServiceTest {

    private final RouteTraceService service =
            new RouteTraceService("src/test/resources/sample-framework");

    private TraceResponse trace(String api, String version, String transferType) {
        return service.trace(new TraceRequest(api, version, transferType, null));
    }

    @Test
    void resolvesOperationAndCommandFromController() {
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", null);
        assertThat(r.getOperationName()).isEqualTo("fundTransferSubmitV2Api");
        assertThat(r.getCommand()).isEqualTo("FundTransferSubmitV2ApiCommand");
    }

    @Test
    void exactVersionResolvesAndFollowsAllBranches() {
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", null);
        assertThat(r.getResolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        assertThat(r.isBaseFallback()).isFalse();
        assertThat(r.getFlow()).contains(
                "R9.4_fundTransferSubmitV2Api", "R9.4_masterFundTransferSubmitApi",
                "R9.4_ftInterbankProcessSubmitDGEApi");
        assertThat(r.getBackendApis()).contains(
                "{{baseUrl}}/bfs/ft/own/submit",
                "{{baseUrl}}/bfs/ft/intra/submit",
                "{{baseUrl}}/bfs/ft/inter/submit",
                "{{baseUrl}}/bfs/ft/inter/fraud-check");
    }

    @Test
    void transferTypeFiltersToSingleBranch() {
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", "INTER");
        assertThat(r.getBackendApis())
                .contains("{{baseUrl}}/bfs/ft/inter/submit")
                .doesNotContain("{{baseUrl}}/bfs/ft/own/submit");
    }

    @Test
    void higherRequestedVersionFallsBackToHighestAvailable() {
        TraceResponse r = trace("/payment/v2/fund/submit", "9.5", null);
        assertThat(r.getResolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        assertThat(r.getResolvedVersion()).isEqualTo("9.4");
        assertThat(r.isBaseFallback()).isFalse();
    }

    @Test
    void lowerVersionWithNoMatchFallsBackToBase() {
        TraceResponse r = trace("/payment/v2/fund/submit", "9.2", null);
        assertThat(r.getResolvedRoute()).isEqualTo("fundTransferSubmitV2Api");
        assertThat(r.isBaseFallback()).isTrue();
        assertThat(r.getBackendApis()).contains("{{baseUrl}}/bfs/ft/base/submit");
    }

    @Test
    void blankVersionUsesBase() {
        TraceResponse r = trace("/payment/v2/fund/submit", "", null);
        assertThat(r.getResolvedRoute()).isEqualTo("fundTransferSubmitV2Api");
        assertThat(r.isBaseFallback()).isTrue();
    }

    @Test
    void choiceOtherwiseTakenWhenNoBranchMatches() {
        // 9.3 master has only an INTER when + otherwise; OWN must hit otherwise.
        TraceResponse r = trace("/payment/v2/fund/submit", "9.3", "OWN");
        assertThat(r.getResolvedRoute()).isEqualTo("R9.3_fundTransferSubmitV2Api");
        assertThat(r.getBackendApis()).contains("{{baseUrl}}/bfs/ft/intra/submit");
    }

    @Test
    void crossVersionDelegationIsFollowed() {
        // 9.3 INTER delegates to the 9.4 interbank route.
        TraceResponse r = trace("/payment/v2/fund/submit", "9.3", "INTER");
        assertThat(r.getFlow()).contains("R9.4_ftInterbankProcessSubmitDGEApi");
        assertThat(r.getBackendApis()).contains("{{baseUrl}}/bfs/ft/inter/submit");
    }
}
