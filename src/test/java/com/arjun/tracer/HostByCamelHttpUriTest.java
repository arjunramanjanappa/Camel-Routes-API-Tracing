package com.arjun.tracer;

import com.arjun.tracer.api.GraphNode;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host route is the one that references {@code CamelHttpUri}; the {@code api}
 * it calls may be set in a calling route, several {@code direct:} hops upstream.
 * The backend must land on that host route, not on an intermediate.
 */
class HostByCamelHttpUriTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="ctx">
                <route id="R9.5_getRate">
                  <from uri="direct:R9.5_getRate"/>
                  <setProperty name="api"><simple>/bfs/rate</simple></setProperty>
                  <to uri="direct:enrichRoute"/>
                </route>
                <route id="enrichRoute">
                  <from uri="direct:enrichRoute"/>
                  <log message="enrich"/>
                  <to uri="direct:callHost"/>
                </route>
                <route id="callHost">
                  <from uri="direct:callHost"/>
                  <setHeader name="CamelHttpUri"><simple>${exchangeProperty.api}</simple></setHeader>
                  <toD uri="${header.CamelHttpUri}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private boolean hostEdge(TraceResponse r, String hostBase, String to) {
        return r.getGraph().getEdges().stream().anyMatch(e ->
                e.from().startsWith("route:" + hostBase + "#") && e.to().equals(to));
    }

    @Test
    void backendLandsOnTheCamelHttpUriHostNotTheIntermediate(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.5_getRate", "", null, null));

        assertThat(r.getFlow()).containsExactly("R9.5_getRate", "enrichRoute", "callHost");
        assertThat(r.getBackendApis()).containsExactly("/bfs/rate");

        // Backend hangs off the CamelHttpUri host instance, not the intermediate/setter.
        assertThat(hostEdge(r, "callHost", "backend:/bfs/rate")).isTrue();
        assertThat(r.getGraph().getEdges()).noneMatch(e ->
                (e.from().startsWith("backend:") || e.to().startsWith("backend:"))
                        && (e.from().equals("route:enrichRoute") || e.to().equals("route:enrichRoute")
                        || e.from().equals("route:R9.5_getRate") || e.to().equals("route:R9.5_getRate")));

        // The host instance is flagged.
        GraphNode host = r.getGraph().getNodes().stream()
                .filter(n -> n.id().startsWith("route:callHost#")).findFirst().orElseThrow();
        assertThat(host.data().get("host")).isEqualTo(true);
    }
}
