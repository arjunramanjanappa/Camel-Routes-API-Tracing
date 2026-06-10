package com.arjun.tracer;

import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.api.TraceResponse;
import com.arjun.tracer.service.RouteTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A common code base with per-country bootstraps. SG pulls in {@code sgContext}
 * (via routeContextRef) and a common file (via import); MY pulls in
 * {@code myContext}. Scoping to a country must include only that country's
 * assembly closure.
 */
class CountryScopingTest {

    private RouteTraceService service;

    private static String beans(String body) {
        return "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\">"
                + body + "</beans:beans>";
    }

    private static String routeContext(String id, String routeId, String backend) {
        return beans("<routeContext id=\"" + id + "\">"
                + "<route id=\"" + routeId + "\">"
                + "<from uri=\"direct:" + routeId + "\"/>"
                + "<setProperty name=\"api\"><simple>" + backend + "</simple></setProperty>"
                + "<log message=\"x\"/></route></routeContext>");
    }

    private static String bootstrap(String ref, String importResource) {
        String imp = importResource == null ? "" : "<import resource=\"" + importResource + "\"/>";
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\">" + imp
                + "<camelContext id=\"camelContext\" xmlns=\"http://camel.apache.org/schema/spring\">"
                + "<routeContextRef ref=\"" + ref + "\"/></camelContext></beans>";
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("SG.xml"), bootstrap("sgContext", "classpath:routes/common.xml"));
        Files.writeString(dir.resolve("MY.xml"), bootstrap("myContext", null));
        Files.createDirectories(dir.resolve("sg"));
        Files.writeString(dir.resolve("sg/sg-routes.xml"),
                routeContext("sgContext", "sgOnlyRoute", "{{baseUrl}}/sg"));
        Files.createDirectories(dir.resolve("my"));
        Files.writeString(dir.resolve("my/my-routes.xml"),
                routeContext("myContext", "myOnlyRoute", "{{baseUrl}}/my"));
        Files.createDirectories(dir.resolve("routes"));
        Files.writeString(dir.resolve("routes/common.xml"),
                routeContext("commonContext", "commonRoute", "{{baseUrl}}/common"));

        service = new RouteTraceService(dir.toString());
    }

    private TraceResponse trace(String country, String operation) {
        return service.trace(new TraceRequest(operation, null, null, null, country));
    }

    @Test
    void listsAvailableCountries() {
        assertThat(service.listCountries(new TraceRequest(null, null, null, null)))
                .containsExactly("MY", "SG");
    }

    @Test
    void sgScopeIncludesItsRefAndImportButNotMyRoutes() {
        TraceResponse sgOwn = trace("SG", "sgOnlyRoute");
        assertThat(sgOwn.getCountry()).isEqualTo("SG");
        assertThat(sgOwn.getFlow()).contains("sgOnlyRoute");
        assertThat(sgOwn.getBackendApis()).contains("{{baseUrl}}/sg");

        TraceResponse sgCommon = trace("SG", "commonRoute");      // reached via <import>
        assertThat(sgCommon.getFlow()).contains("commonRoute");
        assertThat(sgCommon.getWarnings()).noneMatch(w -> w.contains("Route not found"));

        TraceResponse sgSeesMy = trace("SG", "myOnlyRoute");      // MY route excluded
        assertThat(sgSeesMy.getWarnings()).anyMatch(w -> w.contains("Route not found"));
        assertThat(sgSeesMy.getBackendApis()).isEmpty();
    }

    @Test
    void myScopeIncludesOnlyMyRoutes() {
        assertThat(trace("MY", "myOnlyRoute").getFlow()).contains("myOnlyRoute");
        assertThat(trace("MY", "sgOnlyRoute").getWarnings()).anyMatch(w -> w.contains("Route not found"));
        assertThat(trace("MY", "commonRoute").getWarnings()).anyMatch(w -> w.contains("Route not found"));
    }

    @Test
    void noCountryScopeSeesEveryCountrysRoutes() {
        assertThat(trace(null, "sgOnlyRoute").getFlow()).contains("sgOnlyRoute");
        assertThat(trace(null, "myOnlyRoute").getFlow()).contains("myOnlyRoute");
        assertThat(trace(null, "commonRoute").getFlow()).contains("commonRoute");
    }
}
