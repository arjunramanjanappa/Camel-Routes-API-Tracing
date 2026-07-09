package com.uob.tracer;

import com.uob.tracer.api.GraphNode;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported pattern: a route calls the host ({@code direct:callUFWDGE}) and
 * also fires an async ({@code seda:}) call to another route that sets its own
 * api and calls the same host. Both api values must land on the host; the host's
 * own internal {@code camelHttpUri} logic must NOT be shown.
 */
class SedaAsyncHostTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="commonMtyContext">
                <route id="R9.14_getFxRateRoute">
                  <from uri="direct:R9.14_getFxRate"/>
                  <setProperty name="api"><simple>/bfs/fx/rates</simple></setProperty>
                  <to uri="direct:callUFWDGE"/>
                  <to uri="seda:accTXn?waitForTaskToComplete=Never"/>
                </route>
                <route id="accTXnRoute">
                  <from uri="seda:accTXn"/>
                  <setProperty name="api"><simple>/asv/doactivityLogging</simple></setProperty>
                  <to uri="direct:callUFWDGE"/>
                </route>
                <route id="callUFWDGERoute">
                  <from uri="direct:callUFWDGE"/>
                  <choice>
                    <when>
                      <simple>${exchangeProperty[URI_PROTOCOL]} == 'https'</simple>
                      <setProperty name="camelHttpUri"><simple>/asv/fixed</simple></setProperty>
                    </when>
                    <otherwise>
                      <setProperty name="camelHttpUri"><simple>${exchangeProperty[api]}</simple></setProperty>
                    </otherwise>
                  </choice>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private boolean edge(TraceResponse r, String from, String to, String label) {
        return r.getGraph().getEdges().stream().anyMatch(e ->
                e.from().equals(from) && e.to().equals(to)
                        && (label == null ? e.label() == null : label.equals(e.label())));
    }

    /** Edge from a per-call host instance (route:hostBase#N) to a backend. */
    private boolean hostEdge(TraceResponse r, String hostBase, String to) {
        return r.getGraph().getEdges().stream().anyMatch(e ->
                e.from().startsWith("route:" + hostBase + "#") && e.to().equals(to));
    }

    @Test
    void sedaAsyncIsFollowedAndEachCallerGetsItsOwnHostInstance(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("r.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_getFxRate", "", null, null));

        // The async seda route was followed (its ?query stripped).
        assertThat(r.getFlow()).contains("R9.14_getFxRateRoute", "callUFWDGERoute", "accTXnRoute");
        assertThat(edge(r, "route:R9.14_getFxRateRoute", "route:accTXnRoute", "async")).isTrue();

        // Each caller gets its OWN callUFWDGE instance → its backend; the host's
        // internal camelHttpUri literal (/asv/fixed) is NOT shown.
        assertThat(r.getBackendApis())
                .containsExactlyInAnyOrder("/bfs/fx/rates", "/asv/doactivityLogging");
        assertThat(hostEdge(r, "callUFWDGERoute", "backend:/bfs/fx/rates")).isTrue();
        assertThat(hostEdge(r, "callUFWDGERoute", "backend:/asv/doactivityLogging")).isTrue();
        // Two distinct host instances (one per caller).
        assertThat(r.getGraph().getNodes().stream().filter(n -> n.id().startsWith("route:callUFWDGERoute#")).count())
                .isEqualTo(2);

        GraphNode host = r.getGraph().getNodes().stream()
                .filter(n -> n.id().startsWith("route:callUFWDGERoute#")).findFirst().orElseThrow();
        assertThat(host.data().get("host")).isEqualTo(true);
    }
}
