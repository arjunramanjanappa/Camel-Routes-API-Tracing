package com.arjun.tracer;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the reported framework shape where the version lives in the route
 * <em>id</em> while the {@code from} endpoint is un-versioned, and a versioned
 * redirect statically delegates to a different-version operation route:
 *
 * <pre>
 *   R9.18_redirectRoute (from direct:redirectRoute) -> direct:R9.14_getFxRate
 *   R9.14_getFxRateRoute (from direct:R9.14_getFxRate) -> setProperty api -> direct:callUFWDGE
 *   callUFWDGE -> performs the backend call
 * </pre>
 *
 * The whole chain must appear in the flow with version-bearing ids, and the
 * backend must hang off the host route.
 */
class CrossVersionFlowTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="commonMtyContext">
                <route id="R9.18_redirectRoute">
                  <from uri="direct:redirectRoute"/>
                  <to uri="bean:redirectRouteProcessor"/>
                  <to uri="direct:R9.14_getFxRate"/>
                </route>
                <route id="R9.14_getFxRateRoute">
                  <from uri="direct:R9.14_getFxRate"/>
                  <setProperty name="api"><simple>/bfs/fx/rates</simple></setProperty>
                  <to uri="direct:callUFWDGE"/>
                </route>
                <route id="callUFWDGE">
                  <from uri="direct:callUFWDGE"/>
                  <log message="calling ${exchangeProperty.api}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private TraceResponse trace(Path dir, String entry, String version) throws Exception {
        Files.writeString(dir.resolve("common.xml"), ROUTES);
        return new RouteTraceService(dir.toString())
                .trace(new TraceRequest(entry, version, null, null));
    }

    @Test
    void redirectDelegatesToLowerVersionAndCompletesWithVersionedIds(@TempDir Path dir) throws Exception {
        // Enter at the redirect (its from-endpoint is the un-versioned "redirectRoute").
        TraceResponse r = trace(dir, "redirectRoute", "9.18");

        // The whole chain appears, each labelled by its version-bearing route id.
        assertThat(r.getFlow()).containsExactly(
                "R9.18_redirectRoute", "R9.14_getFxRateRoute", "callUFWDGE");
        // No hop was skipped.
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("Route not found"));
        // Backend is tied to the host route that performs the call.
        assertThat(r.getBackendApis()).containsExactly("/bfs/fx/rates");
        assertThat(r.getGraph().getEdges()).anyMatch(e ->
                e.from().equals("route:callUFWDGE") && e.to().equals("backend:/bfs/fx/rates"));
        // The 9.14 operation route is present even though we entered via a 9.18 redirect.
        assertThat(r.getFlow()).contains("R9.14_getFxRateRoute");
    }
}
