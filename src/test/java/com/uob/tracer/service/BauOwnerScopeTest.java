package com.uob.tracer.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BAU-impact check must verify R9.10 even when the NEW app (19.14.0) resolves to R9.14 and never falls back
 * to R9.10 — because the BAU app (19.10.0) still calls R9.10 directly (findroute picks the exact/lowest match).
 * So R9.10 is not on the new app's reachable flow (absent from `ownership`), yet must still be attributed to its
 * API family (by version-stripped base name) and checked. {@link RouteTraceService#bauOwner} is that attribution.
 */
class BauOwnerScopeTest {

    private static final RouteTraceService.RouteOwner ENQUIRY =
            new RouteTraceService.RouteOwner("/enquiry", List.of("enquiryEntry", "R9.14_enquiry"));

    @Test
    void aReachableRouteKeepsItsExactOwner() {
        Map<String, RouteTraceService.RouteOwner> ownership = Map.of("R9.14_enquiry", ENQUIRY);
        Map<String, RouteTraceService.RouteOwner> base = Map.of("enquiry", ENQUIRY);
        assertThat(RouteTraceService.bauOwner("R9.14_enquiry", ownership, base)).isEqualTo(ENQUIRY);
    }

    @Test
    void anUnreachableBauRouteIsAttributedToItsFamilyByBaseName() {
        // R9.14 is the reachable owner; R9.10 is NOT in ownership (new app never falls back to it), but the BAU
        // app still calls it — so it must resolve to the same API, with its OWN id as the display path.
        Map<String, RouteTraceService.RouteOwner> ownership = Map.of("R9.14_enquiry", ENQUIRY);
        Map<String, RouteTraceService.RouteOwner> base = Map.of("enquiry", ENQUIRY);

        RouteTraceService.RouteOwner o = RouteTraceService.bauOwner("R9.10_enquiry", ownership, base);
        assertThat(o).isNotNull();
        assertThat(o.api()).isEqualTo("/enquiry");
        assertThat(o.path()).containsExactly("R9.10_enquiry");   // its own id, not R9.14's chain
    }

    @Test
    void aRouteInNoInScopeFamilyIsNotAttributed() {
        Map<String, RouteTraceService.RouteOwner> ownership = Map.of("R9.14_enquiry", ENQUIRY);
        Map<String, RouteTraceService.RouteOwner> base = Map.of("enquiry", ENQUIRY);
        assertThat(RouteTraceService.bauOwner("R9.10_unrelated", ownership, base)).isNull();
    }
}
