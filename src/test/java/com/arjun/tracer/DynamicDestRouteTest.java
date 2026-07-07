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
 * The SPL pattern: {@code <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>} where a bean
 * builds {@code FINAL_ROUTE_NAME} = client version + a {@code DEST_ROUTE} base constant
 * (e.g. {@code acceptcoreinfo} → {@code R9.14_acceptcoreinfo}, the same version rule used for entry
 * routes). A {@code <choice>} that sets a different {@code DEST_ROUTE} per {@code <when>} — each with
 * its own {@code toD} — must fan out to one resolved route per branch.
 */
class DynamicDestRouteTest {

    private static final String ROUTES = """
            <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
              <routeContext id="commonSplContext">
                <route id="R9.14_mainRoute">
                  <from uri="direct:R9.14_mainRoute"/>
                  <choice>
                    <when>
                      <simple>${header.op} == 'A'</simple>
                      <setProperty name="DEST_ROUTE"><constant>acceptcoreinfo</constant></setProperty>
                      <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                    </when>
                    <when>
                      <simple>${header.op} == 'B'</simple>
                      <setProperty name="DEST_ROUTE"><constant>basicdetails</constant></setProperty>
                      <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                    </when>
                  </choice>
                </route>
                <route id="R9.14_acceptcoreinfo">
                  <from uri="direct:R9.14_acceptcoreinfo"/>
                  <setProperty name="api"><simple>/bfs/accept</simple></setProperty>
                  <to uri="direct:callHost"/>
                </route>
                <route id="R9.14_basicdetails">
                  <from uri="direct:R9.14_basicdetails"/>
                  <setProperty name="api"><simple>/bfs/basic</simple></setProperty>
                  <to uri="direct:callHost"/>
                </route>
                <route id="callHost">
                  <from uri="direct:callHost"/>
                  <log message="host ${exchangeProperty.api}"/>
                </route>
              </routeContext>
            </beans:beans>
            """;

    @Test
    void dynamicDestRouteResolvesViaVersionAndFansOutPerBranch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("spl.xml"), ROUTES);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_mainRoute", "9.14", null, null));

        // Each branch's DEST_ROUTE base resolves to R9.14_<base> and its toD is followed — fan-out.
        assertThat(r.getFlow()).contains("R9.14_mainRoute", "R9.14_acceptcoreinfo", "R9.14_basicdetails");
        assertThat(r.getBackendApis()).contains("/bfs/accept", "/bfs/basic");
        // The dynamic toD no longer breaks the trace.
        assertThat(r.getWarnings()).noneMatch(w -> w.startsWith("Unresolved dynamic target"));
    }

    @Test
    void serviceVersionFromATemplateInsideADynamicallyResolvedDestRouteIsCaptured(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/accept.ftl"), "{ \"serviceVersionNumber\":\"6.0\", \"amount\": 1 }");
        String routes = """
                <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
                  <routeContext id="c">
                    <route id="R9.14_mainRoute">
                      <from uri="direct:R9.14_mainRoute"/>
                      <setProperty name="DEST_ROUTE"><constant>acceptcoreinfo</constant></setProperty>
                      <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                    </route>
                    <route id="R9.14_acceptcoreinfo">
                      <from uri="direct:R9.14_acceptcoreinfo"/>
                      <to uri="framework:templates/accept.ftl"/>
                      <setProperty name="api"><simple>/bfs/accept</simple></setProperty>
                      <to uri="direct:callHost"/>
                    </route>
                    <route id="callHost"><from uri="direct:callHost"/><log message="x"/></route>
                  </routeContext>
                </beans:beans>
                """;
        Files.writeString(dir.resolve("r.xml"), routes);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_mainRoute", "9.14", null, null));

        assertThat(r.getFlow()).contains("R9.14_acceptcoreinfo");
        assertThat(r.getBackendApis()).contains("/bfs/accept");
        assertThat(r.getBackendVersions()).containsEntry("/bfs/accept", "6.0");
    }

    @Test
    void serviceVersionTemplateInTheParentRouteBeforeTheDynamicHop(@TempDir Path dir) throws Exception {
        // The template (serviceVersion) is in the PARENT route, the api in the dest route it dispatches to.
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/accept.ftl"), "{ \"serviceVersionNumber\":\"6.0\" }");
        String routes = """
                <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
                  <routeContext id="c">
                    <route id="R9.14_mainRoute">
                      <from uri="direct:R9.14_mainRoute"/>
                      <to uri="framework:templates/accept.ftl"/>
                      <setProperty name="DEST_ROUTE"><constant>acceptcoreinfo</constant></setProperty>
                      <toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>
                    </route>
                    <route id="R9.14_acceptcoreinfo">
                      <from uri="direct:R9.14_acceptcoreinfo"/>
                      <setProperty name="api"><simple>/bfs/accept</simple></setProperty>
                      <to uri="direct:callHost"/>
                    </route>
                    <route id="callHost"><from uri="direct:callHost"/><log message="x"/></route>
                  </routeContext>
                </beans:beans>
                """;
        Files.writeString(dir.resolve("r.xml"), routes);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_mainRoute", "9.14", null, null));

        assertThat(r.getBackendApis()).contains("/bfs/accept");
        assertThat(r.getBackendVersions()).containsEntry("/bfs/accept", "6.0");
    }

    @Test
    void anUnresolvableDynamicTargetIsStillFlaggedWhenNoDestRouteBaseWasSet(@TempDir Path dir) throws Exception {
        // A dynamic direct: with no preceding DEST_ROUTE-style constant → still flagged for review.
        String routes = """
                <beans:beans xmlns:beans="http://www.springframework.org/schema/beans">
                  <routeContext id="c">
                    <route id="R9.14_x">
                      <from uri="direct:R9.14_x"/>
                      <toD uri="direct:${exchangeProperty[SOME_UNSET_PROP]}"/>
                    </route>
                  </routeContext>
                </beans:beans>
                """;
        Files.writeString(dir.resolve("x.xml"), routes);
        TraceResponse r = new RouteTraceService(dir.toString())
                .trace(new TraceRequest("R9.14_x", "9.14", null, null));

        assertThat(r.getWarnings()).anyMatch(w -> w.startsWith("Unresolved dynamic target"));
    }
}
