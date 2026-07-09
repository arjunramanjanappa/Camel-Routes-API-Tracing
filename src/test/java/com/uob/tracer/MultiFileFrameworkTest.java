package com.uob.tracer;

import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.TraceResponse;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the real bootstrap layout: an {@code SG.xml} that wires the context via
 * {@code <import>} + {@code <routeContextRef>} (no routes of its own), with the
 * actual routes split across separate {@code <beans>/<routeContext>} files —
 * including a {@code direct:} call that crosses file boundaries.
 *
 * <p>The tracer scans every XML file directly, so the assembly references do not
 * need to be followed: routes are indexed globally and link across files.
 */
class MultiFileFrameworkTest {

    // Bootstrap: camelContext + routeContextRef + import — contributes NO routes.
    private static final String SG_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans">
              <import resource="classpath:META-INF/routes/security.xml"/>
              <camelContext id="camelContext" xmlns="http://camel.apache.org/schema/spring">
                <routeContextRef ref="commonMtyContext"/>
              </camelContext>
            </beans>
            """;

    // routeContext A: redirectRoute calls direct:callLCM (defined in file B).
    private static final String CONTEXT_A = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="commonMtyContext">
                <route id="redirectRoute">
                  <from uri="direct:redirectRoute"/>
                  <to uri="bean:redirectRouteProcessor"/>
                  <to uri="direct:callLCM"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    // routeContext B (a different file): defines callLCM with a backend api.
    private static final String CONTEXT_B = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="lcmContext">
                <route id="callLCM">
                  <from uri="direct:callLCM"/>
                  <setProperty name="api"><simple>{{baseUrl}}/lcm/submit</simple></setProperty>
                  <log message="calling lcm"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void routesLinkAcrossFilesAndBootstrapProducesNoWarning(@TempDir Path dir) throws Exception {
        Path routes = Files.createDirectories(dir.resolve("mty-security/src/main/resources/routes"));
        Files.writeString(routes.resolve("SG.xml"), SG_XML);
        Files.writeString(routes.resolve("commonMtyContext.xml"), CONTEXT_A);
        Files.writeString(routes.resolve("lcmContext.xml"), CONTEXT_B);

        RouteTraceService service = new RouteTraceService(dir.toString());
        // No controllers here, so address the route by operation name directly.
        TraceResponse r = service.trace(new TraceRequest("redirectRoute", null, null, null));

        assertThat(r.getResolvedRoute()).isEqualTo("redirectRoute");
        assertThat(r.getFlow()).containsExactly("redirectRoute", "callLCM");   // crossed file boundary
        assertThat(r.getBackendApis()).contains("{{baseUrl}}/lcm/submit");
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("DOM fallback")); // loaded via Camel
    }
}
