package com.arjun.tracer;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.api.VersionDiffReport;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the Release Impact (Compare) scenario for a dynamic
 * {@code <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>} whose DEST_ROUTE base resolves
 * to a versioned route (framework convention: version in the route id AND the from endpoint).
 * With a target version supplied, the dynamic target MUST resolve on both the trace and the diff —
 * it must NOT appear under "needs review".
 */
class DynamicDestRouteDiffTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="c">
                <route id="R9.4_prospectRoute">
                  <from uri="direct:R9.4_prospect"/>
                  <setProperty name="DEST_ROUTE"><constant>prospectDetails</constant></setProperty>
                  <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                </route>
                <route id="R9.3_prospectRoute">
                  <from uri="direct:R9.3_prospect"/>
                  <setProperty name="DEST_ROUTE"><constant>prospectDetails</constant></setProperty>
                  <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                </route>
                <route id="R9.4_prospectDetails">
                  <from uri="direct:R9.4_prospectDetails"/>
                  <setProperty name="api"><simple>/bfs/prospect/v4</simple></setProperty>
                </route>
                <route id="R9.3_prospectDetails">
                  <from uri="direct:R9.3_prospectDetails"/>
                  <setProperty name="api"><simple>/bfs/prospect/v3</simple></setProperty>
                </route>
              </routeContext>
            </beans:beans>
            """;

    private RouteTraceService service(Path dir) throws Exception {
        Files.writeString(dir.resolve("routes.xml"), ROUTES);
        Files.writeString(dir.resolve("Endpoints.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class Endpoints {
                    @CommandHandler @PostMapping("/prospect") public Object prospect(Object b){ return null; }
                }
                """);
        return new RouteTraceService(dir.toString());
    }

    @Test
    void compareAtTargetDoesNotFlagTheDynamicTarget(@TempDir Path dir) throws Exception {
        VersionDiffReport report = service(dir).versionDiff(new TraceRequest(null, "9.4", null, null));
        assertThat(report.getNeedsReview()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
        assertThat(report.getWarnings()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }

    @Test
    void traceAtTargetFollowsTheDynamicTargetToTheVersionedDestRoute(@TempDir Path dir) throws Exception {
        TraceResponse r = service(dir).trace(new TraceRequest("prospect", "9.4", null, null));
        assertThat(r.getFlow()).contains("R9.4_prospectRoute", "R9.4_prospectDetails");
        assertThat(r.getBackendApis()).contains("/bfs/prospect/v4");
        assertThat(r.getNeedsReview()).isEmpty();
    }
}
