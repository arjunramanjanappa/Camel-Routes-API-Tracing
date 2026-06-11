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
 * The reported pattern: a {@code <choice>} sets a different {@code api} value in
 * each branch, then a single host call ({@code direct:postHttpsRequestRIM}) sits
 * <em>after</em> the choice. All three scenarios must show as the host route
 * fanning out to one backend per branch condition.
 */
class ChoiceToHostTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="commonMtyContext">
                <route id="R9.14_getFxRate">
                  <from uri="direct:R9.14_getFxRate"/>
                  <choice>
                    <when>
                      <simple>${exchangeProperty[pbRouteIndicator]} == 'Y'</simple>
                      <setProperty name="api"><simple>/bfs/fx/rates</simple></setProperty>
                    </when>
                    <when>
                      <simple>${exchangeProperty[pbRouteIndicator]} == 'N'</simple>
                      <setProperty name="api"><simple>/bfs/fx/date</simple></setProperty>
                    </when>
                    <otherwise>
                      <setProperty name="api"><simple>/bfs/fx/default</simple></setProperty>
                    </otherwise>
                  </choice>
                  <to uri="direct:postHttpsRequestRIM"/>
                </route>
                <route id="postHttpsRequestRIM">
                  <from uri="direct:postHttpsRequestRIM"/>
                  <log message="host ${exchangeProperty.api}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private boolean hostEdge(TraceResponse r, String hostBase, String to, String label) {
        return r.getGraph().getEdges().stream().anyMatch(e ->
                e.from().startsWith("route:" + hostBase + "#") && e.to().equals(to) && label.equals(e.label()));
    }

    @Test
    void eachBranchBackendFansOutFromTheHostRoute(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fx.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_getFxRate", "", null, null));

        // All three scenarios captured as backends.
        assertThat(r.getBackendApis())
                .containsExactlyInAnyOrder("/bfs/fx/rates", "/bfs/fx/date", "/bfs/fx/default");

        // They fan out from the HOST instance, each labelled with its branch condition.
        assertThat(hostEdge(r, "postHttpsRequestRIM", "backend:/bfs/fx/rates", "Y")).isTrue();
        assertThat(hostEdge(r, "postHttpsRequestRIM", "backend:/bfs/fx/date", "N")).isTrue();
        assertThat(hostEdge(r, "postHttpsRequestRIM", "backend:/bfs/fx/default", "OTHERWISE")).isTrue();

        // The business route itself has no backend edge — it only delegates.
        assertThat(r.getGraph().getEdges()).noneMatch(e ->
                e.from().equals("route:R9.14_getFxRate") && e.to().startsWith("backend:"));
        assertThat(r.getFlow()).contains("R9.14_getFxRate", "postHttpsRequestRIM");
    }
}
