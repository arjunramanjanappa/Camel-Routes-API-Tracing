package com.arjun.tracer;

import com.arjun.tracer.api.CatalogResponse;
import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.api.VersionGroup;
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
    void capturesBackendServiceVersionFromTheFrameworkTemplate() {
        // The own-account route has <to uri="framework:.../precapture.ftl"/> just before
        // its backend; that template's "serviceVersionNumber":"2.2" must attach to the URL.
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", "OWN");

        assertThat(r.getBackendVersions()).containsEntry("{{baseUrl}}/bfs/ft/own/submit", "2.2");

        GraphNode own = r.getGraph().getNodes().stream()
                .filter(n -> n.id().equals("backend:{{baseUrl}}/bfs/ft/own/submit")).findFirst().orElseThrow();
        assertThat(own.data()).containsEntry("serviceVersion", "2.2");
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
    void backendHangsOffAPerCallHostInstanceNotTheBusinessRoute() {
        // setProperty name="api" then to direct:callUFWDGE → backend hangs off a
        // per-call callUFWDGE instance (route:callUFWDGE#N), not the business route.
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", "OWN");
        assertThat(r.getGraph().getEdges()).anyMatch(e ->
                e.from().startsWith("route:callUFWDGE#")
                        && e.to().equals("backend:{{baseUrl}}/bfs/ft/own/submit"));
        assertThat(r.getGraph().getEdges()).noneMatch(e ->
                e.from().equals("route:R9.4_ftOwnAccountProcessSubmitDGEApi")
                        && e.to().startsWith("backend:"));
    }

    @Test
    void eachBranchGetsItsOwnHostInstanceAndBackend() {
        // OWN / INTRA / INTER are separate routes that each call callUFWDGE — they must
        // get separate host instances so each backend is tied to its route.
        TraceResponse r = trace("/payment/v2/fund/submit", "9.4", null);
        long hostInstances = r.getGraph().getNodes().stream()
                .filter(n -> n.id().startsWith("route:callUFWDGE#")).count();
        assertThat(hostInstances).isGreaterThanOrEqualTo(3);
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

    // --- catalog mode (no api) ---

    private CatalogResponse catalog(String version) {
        Object res = service.analyze(new TraceRequest(null, version, null, null));
        assertThat(res).isInstanceOf(CatalogResponse.class);
        return (CatalogResponse) res;
    }

    private VersionGroup group(CatalogResponse cat, String version) {
        return cat.getGroups().stream()
                .filter(g -> g.version().equals(version)).findFirst().orElseThrow();
    }

    @Test
    void noApiProducesCatalogGroupedByVersion() {
        CatalogResponse cat = catalog(null);
        assertThat(cat.getMode()).isEqualTo("catalog");
        // Two controller endpoints: v1 (no routes) and v2 (R9.4, R9.3, BASE).
        assertThat(cat.getOperationCount()).isEqualTo(2);
        assertThat(cat.getVersionsFound()).containsExactly("9.4", "9.3", "BASE", "(no route found)");
        assertThat(cat.getGraph().getNodes()).isNotEmpty();
    }

    @Test
    void catalogVersionGroupResolvesToCorrectRoute() {
        CatalogResponse cat = catalog(null);
        assertThat(group(cat, "9.4").traces()).anySatisfy(t -> {
            assertThat(t.getOperationName()).isEqualTo("fundTransferSubmitV2Api");
            assertThat(t.getResolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
            assertThat(t.getBackendApis()).isNotEmpty();
        });
        assertThat(group(cat, "9.3").traces()).anySatisfy(t ->
                assertThat(t.getResolvedRoute()).isEqualTo("R9.3_fundTransferSubmitV2Api"));
    }

    @Test
    void catalogSurfacesApisWithNoRoute() {
        CatalogResponse cat = catalog(null);
        assertThat(group(cat, "(no route found)").traces()).anySatisfy(t -> {
            assertThat(t.getOperationName()).isEqualTo("fundTransferSubmitApi");
            assertThat(t.getResolvedRoute()).isNull();
        });
    }

    @Test
    void catalogWithVersionShowsOnlyThatReleasesImpactedApis() {
        // 9.4 catalog → only APIs whose entry route IS 9.4 (the impacted ones).
        CatalogResponse c94 = catalog("9.4");
        assertThat(c94.getVersionsFound()).containsExactly("9.4");
        assertThat(group(c94, "9.4").traces()).anySatisfy(t -> {
            assertThat(t.getOperationName()).isEqualTo("fundTransferSubmitV2Api");
            assertThat(t.getResolvedRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        });
        // fundTransferSubmitApi (no route) is NOT in the 9.4 release.
        assertThat(c94.getGroups()).noneSatisfy(g ->
                assertThat(g.version()).isEqualTo("(no route found)"));
    }

    @Test
    void catalogWithVersionThatNothingImplementsIsEmptyWithNotice() {
        // 9.5: no API has an R9.5 route, so nothing is impacted by that release.
        CatalogResponse c95 = catalog("9.5");
        assertThat(c95.getGroups()).isEmpty();
        assertThat(c95.getWarnings()).anyMatch(w -> w.contains("not impacted by version 9.5"));
    }
}
