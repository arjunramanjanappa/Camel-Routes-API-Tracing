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
 * Both shapes of the {@code callUFWDGE} host must be handled identically: the
 * caller's {@code api} attaches to the host barrel, and the host's own internal
 * {@code camelHttpUri} logic (a per-branch choice, or a plain set) is not shown.
 */
class HostVariantsTest {

    private static final String CALLER = """
                <route id="R9.5_getRate">
                  <from uri="direct:R9.5_getRate"/>
                  <setProperty name="api"><simple>/bfs/x</simple></setProperty>
                  <to uri="direct:callUFWDGE"/>
                </route>
            """;

    // Variant 1: a choice sets camelHttpUri differently per branch, then a final set.
    private static final String HOST_WITH_CHOICE = """
                <route id="callUFWDGERoute">
                  <from uri="direct:callUFWDGE"/>
                  <choice>
                    <when>
                      <simple>${exchangeProperty[URI_PROTOCOL]} == 'https'</simple>
                      <setProperty name="camelHttpUri"><simple>/asv/doactivityLogging</simple></setProperty>
                    </when>
                    <when>
                      <simple>${exchangeProperty[URI_PROTOCOL]} == 'http'</simple>
                      <setProperty name="camelHttpUri"><simple>${exchangeProperty[api]}</simple></setProperty>
                    </when>
                  </choice>
                  <setProperty name="camelHttpUri"><simple>${exchangeProperty[api]}</simple></setProperty>
                </route>
            """;

    // Variant 2: a plain camelHttpUri = ${api}.
    private static final String HOST_PLAIN = """
                <route id="callUFWDGERoute">
                  <from uri="direct:callUFWDGE"/>
                  <setProperty name="camelHttpUri"><simple>${exchangeProperty[api]}</simple></setProperty>
                </route>
            """;

    private TraceResponse traceWith(Path dir, String hostRoute) throws Exception {
        String xml = "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + "<routeContext id=\"commonMtyContext\">" + CALLER + hostRoute + "</routeContext></beans:beans>";
        Files.writeString(dir.resolve("r.xml"), xml);
        return new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.5_getRate", "", null, null));
    }

    private void assertHostHandled(TraceResponse r) {
        // host detected
        GraphNode host = r.getGraph().getNodes().stream()
                .filter(n -> n.id().equals("route:callUFWDGERoute")).findFirst().orElseThrow();
        assertThat(host.data().get("host")).isEqualTo(true);
        assertThat(r.getFlow()).contains("R9.5_getRate", "callUFWDGERoute");
        // only the caller's api; the host's internal camelHttpUri logic is not shown
        assertThat(r.getBackendApis()).containsExactly("/bfs/x");
        assertThat(r.getGraph().getEdges()).anyMatch(e ->
                e.from().equals("backend:/bfs/x") && e.to().equals("route:callUFWDGERoute"));
    }

    @Test
    void hostWithPerBranchChoice(@TempDir Path dir) throws Exception {
        assertHostHandled(traceWith(dir, HOST_WITH_CHOICE));
    }

    @Test
    void hostWithPlainSet(@TempDir Path dir) throws Exception {
        assertHostHandled(traceWith(dir, HOST_PLAIN));
    }
}
