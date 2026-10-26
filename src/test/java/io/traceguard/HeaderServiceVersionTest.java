package io.traceguard;

import io.traceguard.api.ApiImpact;
import io.traceguard.api.ImpactIndex;
import io.traceguard.api.TraceRequest;
import io.traceguard.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A route sets {@code <setHeader name="serviceVersionNumber"><constant>2.8</constant></setHeader>} and then
 * calls a header-driven Velocity template whose {@code #else} fallback is 2.4. The effective service version
 * sent is the header value (2.8), not the template's literal fallback (2.4).
 */
class HeaderServiceVersionTest {

    @Test
    void setHeaderServiceVersionWinsOverTemplateElseFallback() {
        RouteTraceService svc = new RouteTraceService("src/test/resources/svc-header");
        ImpactIndex idx = svc.impactIndex(new TraceRequest(
                null, "9.14", null, "src/test/resources/svc-header", null, null, null, List.of()));

        ApiImpact api = idx.getApis().stream().filter(a -> "apiH".equals(a.operation()))
                .findFirst().orElseThrow(() -> new AssertionError("apiH not resolved"));

        assertThat(api.backendVersions().values()).contains("2.8").doesNotContain("2.4");
        assertThat(api.changeBackendVersions().values()).contains("2.8");
    }
}
